package com.example.ztbrowser

import android.app.Dialog
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * 修复记录：
 * - [FIX#15] 使用闭包引用 Dialog 对象，不再依赖 parent.parent.parent
 * - [FIX#16] Network ID 增加十六进制校验
 */
class ZTConfigDialog(
    private val context: Context,
    private val currentNetworkId: String,
    private val currentSubnets: List<String>,
    private val onSave: (networkId: String, subnets: List<String>) -> Unit
) {
    // [FIX#15] 存储 dialog 引用，避免视图层级硬编码
    private var dialog: Dialog? = null

    fun show() {
        val d = Dialog(context).apply {
            setContentView(createView())
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog = d
        d.show()
    }

    private fun createView(): LinearLayout {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 40)
        }

        val title = TextView(context).apply {
            text = "ZeroTier 配置"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 24)
        }
        layout.addView(title)

        // 网络ID输入
        val networkIdLayout = TextInputLayout(context).apply {
            hint = "Network ID（16位十六进制）"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setPadding(0, 0, 0, 16)
        }
        val networkIdInput = TextInputEditText(context).apply {
            setText(currentNetworkId)
            hint = "例如：8056c2e21c000001"
            inputType = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        networkIdLayout.addView(networkIdInput)
        layout.addView(networkIdLayout)

        // 子网输入
        val subnetLayout = TextInputLayout(context).apply {
            hint = "ZeroTier 子网（逗号分隔）"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            helperText = "只有这些网段的地址会走 ZeroTier，其他走直连"
            setPadding(0, 0, 0, 24)
        }
        val subnetInput = TextInputEditText(context).apply {
            setText(currentSubnets.joinToString(","))
            hint = "例如：10.147.0.0/16,172.23.0.0/16"
            inputType = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        subnetLayout.addView(subnetInput)
        layout.addView(subnetLayout)

        // 说明文字
        val hintText = TextView(context).apply {
            text = """
                说明：
                • 不使用系统 VPN，不影响其他 App 网络
                • 浏览器访问 ZT 子网内的地址时自动走 ZeroTier
                • 访问公网时正常直连
                • 需先在 zerotier.com 创建网络并授权本设备
            """.trimIndent()
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, 20)
        }
        layout.addView(hintText)

        // 按钮行
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }

        val cancelBtn = MaterialButton(context).apply {
            text = "取消"
            setTextColor(0xFF666666.toInt())
            setBackgroundColor(0x00000000.toInt())
            setOnClickListener {
                // [FIX#15] 使用闭包引用
                dialog?.dismiss()
            }
        }
        buttonRow.addView(cancelBtn)

        val saveBtn = MaterialButton(context).apply {
            text = "保存并连接"
            setOnClickListener {
                val nwid = networkIdInput.text.toString().trim()
                val subnets = subnetInput.text.toString()
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                // [FIX#16] 校验格式
                var hasError = false

                if (nwid.isEmpty()) {
                    networkIdLayout.error = "请输入 Network ID"
                    hasError = true
                } else if (nwid.length != 16) {
                    networkIdLayout.error = "Network ID 必须为 16 位"
                    hasError = true
                } else if (!isHexString(nwid)) {
                    networkIdLayout.error = "Network ID 必须为十六进制字符 (0-9, a-f)"
                    hasError = true
                } else {
                    networkIdLayout.error = null
                }

                if (!hasError) {
                    onSave(nwid, subnets.ifEmpty { listOf("10.147.0.0/16") })
                    dialog?.dismiss()
                    Toast.makeText(context, "已保存，正在连接...", Toast.LENGTH_SHORT).show()
                }
            }
        }
        buttonRow.addView(saveBtn)

        layout.addView(buttonRow)
        return layout
    }

    private fun isHexString(s: String): Boolean {
        return s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
}
