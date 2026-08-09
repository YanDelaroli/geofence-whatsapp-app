package com.yandelaroli.geofencewhatsapp

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutoSendAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var scheduled = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString().orEmpty()
        if (packageName !in SUPPORTED_PACKAGES) return
        if (AutoSendCoordinator.pendingRuleId(this).isNullOrBlank()) return
        scheduleAttempt()
    }

    override fun onInterrupt() = Unit

    private fun scheduleAttempt() {
        if (scheduled) return
        scheduled = true
        handler.postDelayed({
            scheduled = false
            attemptSend()
        }, 650L)
    }

    private fun attemptSend() {
        val pendingRuleId = AutoSendCoordinator.pendingRuleId(this) ?: return
        val rule = RuleStore(this).find(pendingRuleId) ?: run {
            AutoSendCoordinator.clear(this)
            return
        }
        if (!rule.enabled || !rule.autoSendAuthorized) {
            AutoSendCoordinator.clear(this)
            return
        }

        val root = rootInActiveWindow ?: return
        val packageName = root.packageName?.toString().orEmpty()
        if (packageName !in SUPPORTED_PACKAGES) return

        val button = findSendButton(root)
        if (button != null && button.isEnabled && button.isVisibleToUser) {
            val clicked = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                AutoSendCoordinator.clear(this)
            }
        }
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val packageName = root.packageName?.toString().orEmpty()
        val ids = when (packageName) {
            "com.whatsapp" -> listOf("com.whatsapp:id/send")
            "com.whatsapp.w4b" -> listOf("com.whatsapp.w4b:id/send")
            else -> emptyList()
        }

        ids.forEach { id ->
            root.findAccessibilityNodeInfosByViewId(id)
                ?.firstOrNull { it.isClickable && it.isEnabled && it.isVisibleToUser }
                ?.let { return it }
        }

        val textCandidates = listOf("Enviar", "Send")
        textCandidates.forEach { text ->
            root.findAccessibilityNodeInfosByText(text)
                ?.firstOrNull { node ->
                    node.isClickable && node.isEnabled && node.isVisibleToUser &&
                        (node.className?.toString()?.contains("Button", ignoreCase = true) == true ||
                            node.contentDescription?.toString()?.equals(text, ignoreCase = true) == true)
                }
                ?.let { return it }
        }

        return findByContentDescription(root)
    }

    private fun findByContentDescription(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val description = node.contentDescription?.toString().orEmpty()
        if (node.isClickable && node.isEnabled && node.isVisibleToUser &&
            (description.equals("Enviar", true) || description.equals("Send", true))) {
            return node
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findByContentDescription(child)?.let { return it }
        }
        return null
    }

    companion object {
        private val SUPPORTED_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
    }
}
