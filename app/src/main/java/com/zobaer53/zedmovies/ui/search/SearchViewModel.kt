

package com.zobaer53.zedmovies.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import com.zobaer53.zedmovies.data.common.result.handle
import com.zobaer53.zedmovies.domain.model.MovieModel
import com.zobaer53.zedmovies.domain.model.TvShowModel
import com.zobaer53.zedmovies.domain.usecase.GetMoviesUseCase
import com.zobaer53.zedmovies.domain.usecase.SearchMoviesUseCase
import com.zobaer53.zedmovies.domain.usecase.SearchTvShowsUseCase
import com.zobaer53.zedmovies.data.model.MediaType
import com.zobaer53.zedmovies.data.model.Movie
import com.zobaer53.zedmovies.data.model.TvShow
import com.zobaer53.zedmovies.domain.usecase.GetTvShowsUseCase
import com.zobaer53.zedmovies.ui.smallcomponent.common.EventHandler
import com.zobaer53.zedmovies.ui.smallcomponent.mapper.asMediaTypeModel
import com.zobaer53.zedmovies.ui.smallcomponent.mapper.asMovie
import com.zobaer53.zedmovies.ui.smallcomponent.mapper.asTvShow
import com.zobaer53.zedmovies.ui.smallcomponent.mapper.pagingMap
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@Suppress("TooManyFunctions")
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val getMoviesUseCase: GetMoviesUseCase,
    private val getTvShowsUseCase: GetTvShowsUseCase,
    private val searchMoviesUseCase: SearchMoviesUseCase,
    private val searchTvShowsUseCase: SearchTvShowsUseCase
) : ViewModel(), EventHandler<SearchEvent> {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        loadContent()
    }

    override fun onEvent(event: SearchEvent) = when (event) {
        is SearchEvent.ChangeQuery -> onQueryChange(event.value)
        SearchEvent.Refresh -> onRefresh()
        SearchEvent.Retry -> onRetry()
        SearchEvent.ClearError -> onClearError()
    }

    private fun onQueryChange(query: String) {
        val isSearching = query.isNotBlank()
        _uiState.update {
            it.copy(query = query, isSearching = isSearching)
        }
        searchDebounced(query)
    }

    private fun searchDebounced(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_DURATION)
            search(query)
        }
    }

    private fun search(query: String) = _uiState.update {
        val searchMovies = if (it.isSearching) {
            searchMoviesUseCase(query)
                .pagingMap(MovieModel::asMovie)
                .cachedIn(viewModelScope)
        } else {
            emptyFlow()
        }

        val searchTvShows = if (it.isSearching) {
            searchTvShowsUseCase(query)
                .pagingMap(TvShowModel::asTvShow)
                .cachedIn(viewModelScope)
        } else {
            emptyFlow()
        }

        it.copy(
            searchMovies = searchMovies,
            searchTvShows = searchTvShows
        )
    }

    private fun loadContent() {
        val movieMediaTypes = listOf(
            MediaType.Movie.Discover,
            MediaType.Movie.Trending
        )
        movieMediaTypes.forEach(::loadMovies)

        val tvShowMediaTypes = listOf(
            MediaType.TvShow.Discover,
            MediaType.TvShow.Trending
        )
        tvShowMediaTypes.forEach(::loadTvShows)
    }

    private fun onRefresh() {
        viewModelScope.coroutineContext.cancelChildren()
        loadContent()
    }

    private fun onRetry() {
        onClearError()
        onRefresh()
    }

    private fun onClearError() = _uiState.update { it.copy(error = null) }

    private fun loadMovies(mediaType: MediaType.Movie) = viewModelScope.launch {
        getMoviesUseCase(mediaType.asMediaTypeModel()).handle {
            onLoading { movies ->
                _uiState.update {
                    it.copy(
                        movies = it.movies + (
                            mediaType to movies?.map(MovieModel::asMovie).orEmpty()
                            ),
                        loadStates = it.loadStates + (mediaType to true)
                    )
                }
            }
            onSuccess { movies ->
                _uiState.update {
                    it.copy(
                        movies = it.movies + (mediaType to movies.map(MovieModel::asMovie)),
                        loadStates = it.loadStates + (mediaType to false)
                    )
                }

                if (movies.isEmpty()) {
                    onRefresh()
                }
            }
            onFailure { error -> handleFailure(error = error, mediaType = mediaType) }
        }
    }

    private fun loadTvShows(mediaType: MediaType.TvShow) = viewModelScope.launch {
        getTvShowsUseCase(mediaType.asMediaTypeModel()).handle {
            onLoading { tvShows ->
                _uiState.update {
                    it.copy(
                        tvShows = it.tvShows + (
                            mediaType to tvShows?.map(TvShowModel::asTvShow).orEmpty()
                            ),
                        loadStates = it.loadStates + (mediaType to true)
                    )
                }
            }
            onSuccess { tvShows ->
                _uiState.update {
                    it.copy(
                        tvShows = it.tvShows + (mediaType to tvShows.map(TvShowModel::asTvShow)),
                        loadStates = it.loadStates + (mediaType to false)
                    )
                }
            }
            onFailure { error -> handleFailure(error = error, mediaType = mediaType) }
        }
    }

    private fun handleFailure(error: Throwable, mediaType: MediaType) =
        _uiState.update {
            it.copy(
                error = error,
                isOfflineModeAvailable = it.movies.values.all(List<Movie>::isNotEmpty) ||
                    it.tvShows.values.all(List<TvShow>::isNotEmpty),
                loadStates = it.loadStates + (mediaType to false)
            )
        }
}

private const val SEARCH_DEBOUNCE_DURATION = 500L
