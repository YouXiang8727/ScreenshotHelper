package com.youxiang8727.screenshothelper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

class NodeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "NodeAccessibility"

        var instance: NodeAccessibilityService? = null
        
        // 暴露連線狀態
        private val _isConnected = MutableStateFlow(false)
        val isConnected = _isConnected.asStateFlow()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
        Log.d(TAG, "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        _isConnected.value = false
        instance = null
    }

    // ── 模擬操作 (Gestures) ──────────────────────────────────────────────────

    private fun dispatchClick(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
        Log.d(TAG, "Clicked at ($x, $y)")
    }

    fun dispatchSwipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long) {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, null, null)
        Log.d(TAG, "Swiped from ($x1, $y1) to ($x2, $y2) over ${duration}ms")
    }

    /** 尋找特定節點並點擊 */
    fun clickNode(node: AccessibilityNodeInfo) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        dispatchClick(rect.centerX(), rect.centerY())
    }

    // ── 核心：遍歷節點並儲存為 XML ───────────────────────────────────────────

    fun performNodeDump() {
        val rootNode = rootInActiveWindow ?: run {
            Log.w(TAG, "rootInActiveWindow is null")
            return
        }
        try {
            val document = buildXmlDocument(rootNode)
            saveXmlToFile(document)
        } catch (e: Exception) {
            Log.e(TAG, "Node dump failed", e)
        } finally {
            rootNode.recycle()
        }
    }

    private fun buildXmlDocument(root: AccessibilityNodeInfo): Document {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val hierarchy = document.createElement("hierarchy").also {
            it.setAttribute("rotation", "0")
            document.appendChild(it)
        }
        traverseNode(document, hierarchy, root, 0)
        return document
    }

    private fun traverseNode(document: Document, parent: Element, node: AccessibilityNodeInfo, depth: Int) {
        val element = document.createElement("node").apply {
            setAttribute("index", depth.toString())
            setAttribute("text", node.text?.toString() ?: "")
            setAttribute("resource-id", node.viewIdResourceName ?: "")
            setAttribute("class", node.className?.toString() ?: "")
            setAttribute("package", node.packageName?.toString() ?: "")
            setAttribute("content-desc", node.contentDescription?.toString() ?: "")
            setAttribute("checkable", node.isCheckable.toString())
            setAttribute("checked", node.isChecked.toString())
            setAttribute("clickable", node.isClickable.toString())
            setAttribute("enabled", node.isEnabled.toString())
            setAttribute("focusable", node.isFocusable.toString())
            setAttribute("focused", node.isFocused.toString())
            setAttribute("scrollable", node.isScrollable.toString())
            setAttribute("long-clickable", node.isLongClickable.toString())
            setAttribute("password", node.isPassword.toString())
            setAttribute("selected", node.isSelected.toString())
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            setAttribute("bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
        }
        parent.appendChild(element)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(document, element, child, depth + 1)
            child.recycle()
        }
    }

    private fun saveXmlToFile(document: Document) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "nodes_$timestamp.xml"
        val outputDir = getExternalFilesDir(null) ?: return
        if (!outputDir.exists()) outputDir.mkdirs()
        val outputFile = File(outputDir, fileName)
        FileOutputStream(outputFile).use { fos ->
            TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.INDENT, "yes")
                setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
                setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            }.transform(DOMSource(document), StreamResult(fos))
        }
        Log.d(TAG, "Nodes saved → ${outputFile.absolutePath}")
    }

    fun findNodeByText(text: String): List<AccessibilityNodeInfo> {
        Log.d(TAG, "findNodeByText: $text")
        val rootNode = rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        traverseNode(rootNode, result)
        return result.filter {
            it.text?.toString() == text
        }
    }

    fun findNodeById(id: String): List<AccessibilityNodeInfo> {
        Log.d(TAG, "findNodeById: $id")
        val rootNode = rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        traverseNode(rootNode, result)
        return result.filter {
            it.viewIdResourceName == id
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        result.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(
                child,
                result
            )
        }
    }
}
