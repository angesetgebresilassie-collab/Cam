package com.cam.photos.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cam.photos.ui.library.LibraryGridScreen
import com.cam.photos.ui.library.mediaPagingFlow
import com.cam.photos.ui.theme.GlassSurface

sealed class PhotosTab(val route: String, val label: String) {
    data object Library : PhotosTab("library", "Library")
    data object ForYou : PhotosTab("for_you", "For You")
    data object Albums : PhotosTab("albums", "Albums")
    data object Search : PhotosTab("search", "Search")

    companion object {
        val all = listOf(Library, ForYou, Albums, Search)
    }
}

@Composable
fun PhotosAppRoot() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagingFlow = mediaPagingFlow(context, scope)

    Scaffold(
        bottomBar = {
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                cornerRadius = 28
            ) {
                GlassTabBar(navController)
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            NavHost(
                navController = navController,
                startDestination = PhotosTab.Library.route
            ) {
                composable(PhotosTab.Library.route) {
                    LibraryGridScreen(pagingFlow = pagingFlow)
                }
                composable(PhotosTab.ForYou.route) { ForYouScreenPlaceholder() }
                composable(PhotosTab.Albums.route) { AlbumsScreenPlaceholder() }
                composable(PhotosTab.Search.route) { SearchScreenPlaceholder() }
            }
        }
    }
}

@Composable
private fun GlassTabBar(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationBar(
        containerColor = Color.Transparent,
        tonalElevation = 0.dp
    ) {
        PhotosTab.all.forEach { tab ->
            val selected =
                currentDestination?.hierarchy?.any { it.route == tab.route } == true

            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(tab.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { TabIcon(tab) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = Color.Transparent
                )
            )
        }
    }
}

@Composable
private fun TabIcon(tab: PhotosTab) {
    when (tab) {
        PhotosTab.Library ->
            Icon(Icons.Filled.DateRange, contentDescription = tab.label)
        PhotosTab.ForYou ->
            Icon(Icons.Filled.Favorite, contentDescription = tab.label)
        PhotosTab.Albums ->
            Icon(Icons.Filled.AccountCircle, contentDescription = tab.label)
        PhotosTab.Search ->
            Icon(Icons.Filled.Search, contentDescription = tab.label)
    }
}

@Composable
fun ForYouScreenPlaceholder() {
    Box(Modifier.fillMaxSize(), Alignment.Center) { Text("For You") }
}

@Composable
fun AlbumsScreenPlaceholder() {
    Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Albums") }
}

@Composable
fun SearchScreenPlaceholder() {
    Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Search") }
}
