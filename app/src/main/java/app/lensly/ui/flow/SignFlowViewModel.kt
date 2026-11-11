package app.lensly.ui.flow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Keeps one [SignFlow] for the sign route, so a rotation does not restart the scan. The flow does the work. This class only
 * runs its suspending calls on the view model scope, which is cancelled when the route is left.
 */
class SignFlowViewModel(private val flow: SignFlow) : ViewModel() {
    val state: StateFlow<SignStep> = flow.state

    fun onScanned(text: String) {
        viewModelScope.launch { flow.onScanned(text) }
    }

    fun scanAgain() = flow.scanAgain()

    fun approve() = flow.approve()

    /** The password is zeroed by the flow when it has been checked. */
    fun confirm(password: CharArray) {
        viewModelScope.launch { flow.confirm(password) }
    }

    fun back() = flow.back()

    fun leave() = flow.leave()
}
