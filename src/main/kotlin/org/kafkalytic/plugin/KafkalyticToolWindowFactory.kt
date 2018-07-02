package org.kafkalytic.plugin

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.impl.BackgroundableProcessIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.*
import java.util.regex.Pattern
import javax.swing.*
import javax.swing.event.*
import javax.swing.tree.*

class KafkalyticToolWindowFactory : ToolWindowFactory {
    private val LOG = Logger.getInstance("Kafkalytic")
    private val ADD_ICON by lazy {IconLoader.getIcon("/general/add.png")}
    private val REMOVE_ICON by lazy {IconLoader.getIcon("/general/remove.png")}
    private val REFRESH_ICON by lazy {IconLoader.getIcon("/actions/refresh.png")}
    private var toolWindow: ToolWindow? = null
    private var project: Project? = null
    private val zRoot by lazy {DefaultMutableTreeNode("Kafka")}
    private val treeModel by lazy {DefaultTreeModel(zRoot)}
    private val tree by lazy {Tree(treeModel)}
    private val tableModel by lazy {TableModel()}
    private val addButton by lazy {AddAction()}
    private val removeButton by lazy {RemoveAction()}
    private var actionToolBar: ActionToolbar? = null

    val config: KafkaStateComponent = ServiceManager.getService(KafkaStateComponent::class.java)

    companion object Formatter {
        private val formatter = NumberFormat.getInstance(Locale.US) as DecimalFormat;

        init {
            val symbols = formatter.getDecimalFormatSymbols();
            symbols.setGroupingSeparator(' ');
            formatter.setDecimalFormatSymbols(symbols);
        }

        fun format(int: Int) : String {
            return formatter.format(int)
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        this.project = project
        this.toolWindow = toolWindow
        val contentFactory = ContentFactory.SERVICE.getInstance()
        val content = contentFactory!!.createContent(createToolWindowPanel(), "", false)
        toolWindow.contentManager!!.addContent(content)
    }

    private fun createToolWindowPanel(): JComponent {
        val toolPanel = JPanel(BorderLayout())
        toolPanel.add(getToolbar(), BorderLayout.PAGE_START)

        val panel = JSplitPane(JSplitPane.VERTICAL_SPLIT)

        config.clusters?.forEach{LOG.info("init node:" + it);zRoot.add(KRootTreeNode(it.value))}
        tree.expandPath(TreePath(zRoot))

//        tree.cellRenderer = ZkTreeCellRenderer()
        tree.addTreeSelectionListener(object : TreeSelectionListener {
            override fun valueChanged(e: TreeSelectionEvent?) {
                val node = e?.path?.lastPathComponent  as DefaultMutableTreeNode
                if (node != null) {
                    tableModel.updateDetails(node)
                }
            }
        })

        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    val paths = tree.selectionPaths
                    if (paths.size == 0) {
                        return
                    }

                    val menu = object: JPopupMenu() {
                        fun add(name: String, task: () -> Unit) {
                            add(JMenuItem(object: AbstractAction(name) {
                                override fun actionPerformed(e: ActionEvent) {
                                    task()
                                }
                            }))
                        }
                    }
                    menu.add("Select topics", {
                        val pattern = Messages.showInputDialog("Enter selection regexp", "Select brokers", Messages.getQuestionIcon())
                        if (pattern != null && pattern.length > 0) {
                            val parent = (paths.first().path[1] as KRootTreeNode).topics
                            tree.selectionModel.selectionPaths = (parent.children().asSequence()
                                    .filter { Pattern.matches(pattern, (it as DefaultMutableTreeNode).userObject.toString()) }
                                    .map({
                                TreePath((tree.getModel() as DefaultTreeModel).getPathToRoot(it as TreeNode))
                            }).toList()).toTypedArray()
                            info(tree.selectionModel.selectionPaths.size.toString() + " brokers were selected.")
                        }
                    })
                    val topicNodes = paths.filter {it.lastPathComponent is KTopicTreeNode}.map{it.lastPathComponent as KTopicTreeNode}
                    val topics = topicNodes.map {it.getTopicName()}
                    if (topics.size > 0) {
                        menu.add("Delete topic(s)", {
                            if (Messages.OK == Messages.showOkCancelDialog(
                                            "You are about to delete following topics " + topics.joinToString {"\n"},
                                            "Kafka", Messages.getQuestionIcon())) {
                                background("Deleting kafka topics", {
                                    val cluster = topicNodes.first().cluster
                                    cluster.delete(topics)
                                    cluster.refreshTopics()
                                    treeModel.reload(cluster.topics)
                                    info("" + topics.size + " brokers were deleted.")
                                })
                            }
                        })
                    }
                    if (paths.size == 1) {
                        val path = paths.first()
                        val last = path.lastPathComponent
                        if (last is KTopicTreeNode) {
                            menu.add("Consume from " + last.getTopicName(), {
                                val dialog = ConsumeDialog(last.getTopicName())
                                dialog.show()
                                LOG.info("progress:" + ProgressManager.getInstance().progressIndicator)
                                ApplicationManager.getApplication().invokeLater({
                                    val props = (path.path[1] as KRootTreeNode).getClusterProperties()
                                    val consumer = when (dialog.getMode()) {
                                        0 -> WaitMessageConsumer(project!!,
                                                last.getTopicName(),
                                                props,
                                                dialog.getKeyDeserializer(),
                                                dialog.getValueDeserializer(),
                                                dialog.getWaitFor())
                                        1 -> RecentMessageConsumer(project!!,
                                                last.getTopicName(),
                                                props,
                                                dialog.getKeyDeserializer(),
                                                dialog.getValueDeserializer(),
                                                dialog.getDecrement())
                                        2 -> SpecificMessageConsumer(project!!,
                                                last.getTopicName(),
                                                props,
                                                dialog.getKeyDeserializer(),
                                                dialog.getValueDeserializer(),
                                                dialog.getPartition(),
                                                dialog.getOffset())
                                        else -> throw IllegalArgumentException()
                                    }
                                    ProgressManager.getInstance().runProcessWithProgressAsynchronously(
                                            consumer, BackgroundableProcessIndicator(consumer))

                                })
                            })
                        }
                        menu.add("Refresh", {
                            val cluster = path.path[1] as KRootTreeNode
                            if (last is KRootTreeNode) {
                                background("Refresh cluster", {
                                    cluster.refreshTopics()
                                    cluster.refreshBrokers()
                                    treeModel.reload(cluster)
                                })
                            } else if (last == cluster.topics) {
                                background("Refresh topics", {
                                    cluster.refreshTopics()
                                    treeModel.reload(cluster.topics)
                                })
                            }
                        })
                        if (last is KRootTreeNode) {
                            menu.add("Remove cluster " + last.toString(), {
                                removeCluster()
                            })
                        }
                    }
                    menu.show(tree, e.x, e.y)
                }
            }
        })

        tree.isRootVisible = false
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent?) {
                val node = event!!.path.lastPathComponent
                if (node is KRootTreeNode) {
                    background("Reading cluster brokers") {
                        node.expand()
                        treeModel.reload(node)
                    }
                }
            }

            override fun treeCollapsed(event: TreeExpansionEvent?) {
            }
        })
        val details = JBTable(tableModel)
        details.setFillsViewportHeight(false)
        details.setShowColumns(true)

        panel.topComponent = JBScrollPane(tree)
        panel.bottomComponent = JBScrollPane(details)
        panel.setResizeWeight(0.7)
        panel.dividerSize = 2
        toolPanel.add(panel, BorderLayout.CENTER)

        return toolPanel
    }

    private fun removeCluster() {
        val clusterNode = tree.selectionPaths[0].lastPathComponent as KRootTreeNode
        if (Messages.OK == Messages.showOkCancelDialog(
                        "You are about to delete Kafka cluster " + clusterNode.userObject,
                        "Kafka", Messages.getQuestionIcon())) {
            zRoot.remove(clusterNode)
            treeModel.reload(zRoot)
            config.removeCluster(clusterNode.userObject as Map<String, String>)
        }
    }

    fun updateTree(text: String) {
    }

    private fun getToolbar(): JComponent {
        val panel = JPanel()

        panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)

        val group = DefaultActionGroup()
//        val refreshButton = RefreshAction()
//        group.add(refreshButton)
        group.add(addButton)
        group.add(removeButton)
        removeButton.templatePresentation.isEnabled = false

        actionToolBar = ActionManager.getInstance().createActionToolbar("CabalTool", group, true)

        panel.add(actionToolBar!!.component!!)


        val searchTextField = SearchTextField()
        searchTextField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent?) {
                updateTree(searchTextField.text!!)
            }
        })


        panel.add(searchTextField)
        return panel
    }

    private fun addCluster() {
        val dialog = CreateClusterDialog()
        dialog.show()
        if (dialog.exitCode == Messages.OK) {
            background("Adding Kafka cluster " + dialog.inputString, {
                LOG.info("added:" + dialog.getCluster())
                zRoot.add(KRootTreeNode(dialog.getCluster()))
                treeModel.reload(zRoot)
                config.addCluster(dialog.getCluster())
            })
        }
    }

    inner class AddAction : AnAction("Add", "Add Kafka cluster node", ADD_ICON) {
        override fun actionPerformed(e: AnActionEvent?) {
            addCluster()
        }
    }

    inner class RemoveAction : AnAction("Remove","Remove Kafka cluster node", REMOVE_ICON), AnAction.TransparentUpdate {
        override fun actionPerformed(e: AnActionEvent?) {
            removeCluster()
        }

        override fun update (e: AnActionEvent) {
            e.getPresentation().setEnabled(removeEnabled());
        }
    }

    private fun removeEnabled(): Boolean {
        if (tree.selectionPaths == null) {
            return false
        }
        return tree.selectionPaths.fold(true, {a, v ->
            val path = v.lastPathComponent
            a && (path is KRootTreeNode) && (path?.isLeaf || path is KRootTreeNode)})
    }


    private fun error(message: String, e: Exception) {
        LOG.error(message, e)
        ApplicationManager.getApplication().invokeLater({
            Messages.showErrorDialog(message + e.toString(), "Kafka")
        })
    }

    private fun info(message: String) {
        LOG.info(message)
        ApplicationManager.getApplication().invokeLater({
            Messages.showInfoMessage(message, "Kafka")
        })
    }

    fun background(title: String, task: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            ProgressManager.getInstance().run(object: Task.Backgroundable(project, title, false) {
                override fun run(indicator: ProgressIndicator) {
                    task()
                    LOG.info("background task complete:" + title)
                }
            })
        })
    }

}
