import FerrostarCore

// The class `FerrostarCore` shadows the module of the same name, so `FerrostarCore.NavigationState`
// cannot be written, and RemaplyNavigationCore declares its own `NavigationState`. This module has
// no such clash, so it can name Ferrostar's type for the SDK's other modules.
//
// Not part of the SDK's API.

public typealias EngineNavigationState = NavigationState
