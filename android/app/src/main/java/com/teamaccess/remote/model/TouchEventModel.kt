package com.teamaccess.remote.model

object TouchConstants {
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
    const val ACTION_CANCEL = 3
    const val KEY_EVENT = 4
    const val SCROLL = 5

    // Remote virtual navigation keys
    const val KEY_BACK = 1
    const val KEY_HOME = 2
    const val KEY_RECENTS = 3
}

data class TouchEventModel(
    val action: Int,
    val pointerId: Int = 0,
    val normalizedX: Float = 0f,
    val normalizedY: Float = 0f,
    val extraData: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)
