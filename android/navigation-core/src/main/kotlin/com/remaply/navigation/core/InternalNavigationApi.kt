package com.remaply.navigation.core

/**
 * Marks declarations that are public only so the SDK's own modules (navigation-ui) can use them.
 *
 * They expose the underlying engine and may change or disappear in any release, including patch
 * releases. Do not use them in apps.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Internal Remaply Navigation SDK API. It may change in any release; do not use it in apps.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class InternalNavigationApi
