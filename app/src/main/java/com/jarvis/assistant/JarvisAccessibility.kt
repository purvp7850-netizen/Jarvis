package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Lets Jarvis press Home/Back, tap on-screen text, scroll, type and read the screen. */
class JarvisAccessibility : AccessibilityService() {

    companion object {
        @Volatile
        var instance: JarvisAccessibility? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun globalAction(name: String): Boolean = when (name.lowercase()) {
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "power_menu" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        "lock_screen" ->
            if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false
        "screenshot" ->
            if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) else false
        else -> false
    }

    fun tapText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text) ?: return false
        for (n in nodes) {
            var node: AccessibilityNodeInfo? = n
            while (node != null && !node.isClickable) node = node.parent
            if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scroll(down: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findScrollable(root) ?: return false
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return target.performAction(action)
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findScrollable(child)
            if (hit != null) return hit
        }
        return null
    }

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "Nothing readable on screen."
        val sb = StringBuilder()
        collect(root, sb)
        return sb.toString().ifBlank { "The screen has no readable text." }
    }

    private fun collect(node: AccessibilityNodeInfo, sb: StringBuilder) {
        if (sb.length > 1500) return
        val label = node.text?.toString() ?: node.contentDescription?.toString()
        if (!label.isNullOrBlank()) sb.append(label).append(". ")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, sb)
        }
    }
}
