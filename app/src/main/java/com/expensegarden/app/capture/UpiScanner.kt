package com.expensegarden.app.capture

sealed interface ScannerLaunch {
    data class Component(
        val pkg: String, val cls: String, val action: String, val category: String,
        val extraKey: String, val extraValue: String,
    ) : ScannerLaunch
    data class DeepLink(val pkg: String, val uri: String) : ScannerLaunch
    data class AppHome(val pkg: String) : ScannerLaunch
}

sealed interface PersonAction {
    data class Open(val pkg: String) : PersonAction
    data class Pick(val clearSaved: Boolean) : PersonAction
    data object NoneInstalled : PersonAction
}

object UpiScanner {
    fun attempts(pkg: String): List<ScannerLaunch> = TODO()
    fun opensScanner(pkg: String): Boolean = TODO()
    fun hint(pkg: String): String = TODO()
    fun personAction(saved: String?, installed: List<String>): PersonAction = TODO()
}
