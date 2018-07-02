package org.kafkalytic.plugin

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import org.apache.kafka.common.serialization.*
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.*
import javax.swing.event.ChangeEvent
import javax.swing.event.ChangeListener


class ConsumeDialog(topic: String) : DialogWrapper(false), ChangeListener {
    private val LOG = Logger.getInstance(this::class.java)

    private var waitFor: JTextField? = null
    private var decrement: JTextField? = null
    private var partition: JTextField? = null
    private var offset: JTextField? = null
    private var keyDeserializer: JComboBox<String>? = null
    private var valueDeserializer: JComboBox<String>? = null
    private var radios: List<JRadioButton>? = null
    override fun stateChanged(e: ChangeEvent?) {
        waitFor?.isEnabled = radios!![0].isSelected
        decrement?.isEnabled = radios!![1].isSelected
        partition?.isEnabled = radios!![2].isSelected
        offset?.isEnabled = radios!![2].isSelected
        LOG.info("radios:" + radios!![2].isSelected + ":" + radios!![1].isSelected)
    }

    init {
        setTitle("Configure Kafka consumer for topic " + topic)
        init()
    }

    override fun createCenterPanel(): JPanel {
        val certPanel = JPanel(BorderLayout())

        val gridLayout = GridLayout(0, 2)
        gridLayout.hgap = 2
        gridLayout.vgap = 2
        val deserizalizerSubPanel = JPanel(gridLayout)
        val deserializers = arrayOf(StringDeserializer::class.java,
                ByteArrayDeserializer::class.java,
                IntegerDeserializer::class.java,
                LongDeserializer::class.java,
                DoubleDeserializer::class.java).map{it.getSimpleName()}.toTypedArray()
        keyDeserializer = ComboBox(deserializers)
        keyDeserializer?.preferredSize = Dimension(40, 24)
        valueDeserializer = ComboBox(deserializers)
        valueDeserializer?.preferredSize = Dimension(40, 24)
        valueDeserializer?.selectedIndex = 1
        deserizalizerSubPanel.add(JLabel("Key deserializer"))
        deserizalizerSubPanel.add(keyDeserializer)
        deserizalizerSubPanel.add(JLabel("Value deserializer"))
        deserizalizerSubPanel.add(valueDeserializer)
        deserizalizerSubPanel.border = BorderFactory.createLineBorder(Color.GRAY, 1, true)
        certPanel.add(deserizalizerSubPanel, BorderLayout.CENTER)

        val methodSubPanel = JPanel(GridLayout(3, 5))
        radios = arrayOf("Wait for ", "Recent ", "Specific message at ").map{JRadioButton(it)}
        methodSubPanel.add(radios!![0])
        waitFor = JTextField()
        methodSubPanel.add(waitFor)
        methodSubPanel.add(JLabel(" messages"))
        (1..3).forEach{methodSubPanel.add(JLabel(""))}

        methodSubPanel.add(radios!![1])
        decrement = JTextField()
        methodSubPanel.add(decrement)
        methodSubPanel.add(JLabel(" messages"))
        (1..3).forEach{methodSubPanel.add(JLabel(""))}
        methodSubPanel.add(radios!![2])
        partition = JTextField()
        methodSubPanel.add(partition)
        methodSubPanel.add(JLabel(" partition"))
        offset = JTextField()
        methodSubPanel.add(offset)
        methodSubPanel.add(JLabel(" offset"))
        certPanel.add(methodSubPanel, BorderLayout.SOUTH)
        val radioGroup = ButtonGroup()
        radios!!.forEach {
            radioGroup.add(it)
            it.addChangeListener(this)
        }
//        radios!![2].isSelected = false
        radios!![1].isSelected = true
        stateChanged(null)

        return certPanel
    }

    fun getKeyDeserializer()= "org.apache.kafka.common.serialization." + keyDeserializer!!.selectedItem
    fun getValueDeserializer()= "org.apache.kafka.common.serialization." + valueDeserializer!!.selectedItem
    fun getDecrement() =
            if (radios!![1].isSelected) {
                decrement!!.text.toInt()
            } else {
                0
            }

    fun getPartition() =
            if (radios!![2].isSelected) {
                partition!!.text.toInt()
            } else {
                0
            }

    fun getOffset() =
            if (radios!![2].isSelected) {
                offset!!.text.toLong()
            } else {
                0
            }
    fun getWaitFor() =
            if (radios!![0].isSelected) {
                waitFor!!.text.toInt()
            } else {
                0
            }
    fun getMode() = if (radios!![0].isSelected) 0 else if (radios!![1].isSelected) 1 else 2
}
