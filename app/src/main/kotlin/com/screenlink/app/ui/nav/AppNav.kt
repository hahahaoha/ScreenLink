package com.screenlink.app.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.screenlink.app.ui.controller.ControllerScreen
import com.screenlink.app.ui.home.HomeScreen
import com.screenlink.app.ui.host.HostScreen
import com.screenlink.app.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val HOST = "host"
    const val CONTROLLER = "controller"
    const val SETTINGS = "settings"
}

@Composable
fun AppNav() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = {
            slideInHorizontally(animationSpec = tween(300)) { it / 6 } + fadeIn(tween(200))
        },
        exitTransition = {
            slideOutHorizontally(animationSpec = tween(220)) { -it / 8 } + fadeOut(tween(150))
        },
        popEnterTransition = {
            slideInHorizontally(animationSpec = tween(300)) { -it / 8 } + fadeIn(tween(200))
        },
        popExitTransition = {
            slideOutHorizontally(animationSpec = tween(220)) { it / 6 } + fadeOut(tween(150))
        },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onHost = { navController.navigate(Routes.HOST) },
                onController = { navController.navigate(Routes.CONTROLLER) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.HOST) {
            HostScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.CONTROLLER) {
            ControllerScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
