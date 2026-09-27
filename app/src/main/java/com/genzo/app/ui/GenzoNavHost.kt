package com.genzo.app.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.genzo.app.domain.GenzoRepository
import com.genzo.app.ui.chat.ChatScreen
import com.genzo.app.ui.chat.ChatViewModel
import com.genzo.app.ui.common.SimpleViewModelFactory
import com.genzo.app.ui.conversations.ConversationListScreen
import com.genzo.app.ui.conversations.ConversationListViewModel
import com.genzo.app.ui.onboarding.OnboardingScreen
import com.genzo.app.ui.onboarding.OnboardingViewModel

private const val ROUTE_ONBOARDING = "onboarding"
private const val ROUTE_USERNAME_SETUP = "username_setup"
private const val ROUTE_CONVERSATIONS = "conversations"
private const val ROUTE_CHAT = "chat/{peerId}"

@Composable
fun GenzoNavHost(repository: GenzoRepository) {
    val navController: NavHostController = rememberNavController()
    val startDestination = if (repository.isOnboarded) ROUTE_CONVERSATIONS else ROUTE_ONBOARDING

    NavHost(navController = navController, startDestination = startDestination) {
        composable(ROUTE_ONBOARDING) {
            OnboardingScreen(onContinue = { navController.navigate(ROUTE_USERNAME_SETUP) })
        }
        composable(ROUTE_USERNAME_SETUP) {
            val viewModel: OnboardingViewModel = viewModel(factory = SimpleViewModelFactory { OnboardingViewModel(repository) })
            com.genzo.app.ui.onboarding.UsernameSetupScreen(
                viewModel = viewModel,
                onDone = {
                    navController.navigate(ROUTE_CONVERSATIONS) {
                        popUpTo(ROUTE_ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(ROUTE_CONVERSATIONS) {
            val viewModel: ConversationListViewModel =
                viewModel(factory = SimpleViewModelFactory { ConversationListViewModel(repository) })
            ConversationListScreen(
                viewModel = viewModel,
                onOpenConversation = { peerId -> navController.navigate("chat/$peerId") },
            )
        }
        composable(ROUTE_CHAT) { backStackEntry ->
            val peerId = backStackEntry.arguments?.getString("peerId") ?: return@composable
            val viewModel: ChatViewModel =
                viewModel(factory = SimpleViewModelFactory { ChatViewModel(repository, peerId) })
            ChatScreen(viewModel = viewModel, peerId = peerId, onBack = { navController.popBackStack() })
        }
    }
}
