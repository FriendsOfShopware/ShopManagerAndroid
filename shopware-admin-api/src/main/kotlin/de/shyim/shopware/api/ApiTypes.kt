package de.shyim.shopware.api

// Value types produced by the API facades (InstanceApi.languages, StateMachineApi)

data class LanguageOption(val id: String, val name: String, val localeCode: String?)

data class StateTransition(
    val actionName: String,
    val toStateName: String,
    val displayName: String, // localized target-state name from the transition response
    val url: String, // /_action/state-machine/{entity}/{id}/state/{action}
)
