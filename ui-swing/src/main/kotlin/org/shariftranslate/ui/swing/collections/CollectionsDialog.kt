package org.shariftranslate.ui.swing.collections

import org.shariftranslate.core.collections.CollectionKind
import org.shariftranslate.core.collections.CollectionRepository
import org.shariftranslate.core.collections.ItemSort
import org.shariftranslate.core.collections.TextCollection
import org.shariftranslate.core.localization.LocalizationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.GridLayout
import javax.swing.*

/** Collection manager and item viewer, backed by the same repository used by popup actions. */
class CollectionsDialog(
    owner: Frame,
    private val repository: CollectionRepository,
    private val scope: CoroutineScope,
    private val localizer: LocalizationManager
) : JDialog(owner, false) {
    private fun label(key: String) = localizer.getString("collections.$key")
    private val listModel = DefaultListModel<TextCollection>()
    private val collectionList = JList(listModel).apply { selectionMode = ListSelectionModel.SINGLE_SELECTION }
    private val itemModel = DefaultListModel<String>()
    private val itemList = JList(itemModel).apply { selectionMode = ListSelectionModel.SINGLE_SELECTION }
    private val sortBox = JComboBox(ItemSort.entries.toTypedArray())
    private var rendering = false

    init {
        title = label("title")
        minimumSize = Dimension(720, 430)
        setSize(820, 520)
        setLocationRelativeTo(owner)
        defaultCloseOperation = HIDE_ON_CLOSE
        sortBox.renderer = DefaultListCellRenderer().also { renderer ->
            sortBox.setRenderer { list, value, index, selected, focus ->
                renderer.getListCellRendererComponent(list, value?.let { label("sort_${it.name.lowercase()}") } ?: "", index, selected, focus)
            }
        }
        collectionList.cellRenderer = DefaultListCellRenderer().also { renderer ->
            collectionList.setCellRenderer { list, value, index, selected, focus ->
                renderer.getListCellRendererComponent(list, "${if (value.pinned) "📌 " else ""}${if (value.kind == CollectionKind.SYSTEM) label("favorites") else value.name} (${value.items.size})", index, selected, focus)
            }
        }
        itemList.cellRenderer = DefaultListCellRenderer().also { renderer ->
            itemList.setCellRenderer { list, value, index, selected, focus ->
                renderer.getListCellRendererComponent(list, value.replace('\n', ' '), index, selected, focus)
            }
        }
        val collectionButtons = JPanel(GridLayout(2, 3, 4, 4)).apply {
            add(button("new") { promptName(null)?.let { name -> perform { repository.create(name) } } })
            add(button("rename") { selectedUser()?.let { c -> promptName(c.name)?.let { name -> perform { repository.rename(c.id, name) } } } })
            add(button("delete") { selectedUser()?.let { c ->
                if (JOptionPane.showConfirmDialog(this@CollectionsDialog, label("delete_confirm"), label("delete"), JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
                    perform { repository.delete(c.id) }
            } })
            add(button("pin") { selectedUser()?.let { c -> perform { repository.setPinned(c.id, !c.pinned) } } })
            add(button("up") { selectedUser()?.let { c -> perform { repository.reorder(c.id, -1) } } })
            add(button("down") { selectedUser()?.let { c -> perform { repository.reorder(c.id, 1) } } })
        }
        val itemButtons = JPanel(GridLayout(2, 3, 4, 4)).apply {
            add(button("add_text") {
                selectedCollection()?.let { c ->
                    val text = JOptionPane.showInputDialog(this@CollectionsDialog, label("text_prompt"))
                    if (!text.isNullOrBlank()) perform { repository.add(c.id, text) }
                }
            })
            add(button("remove") { val c = selectedCollection(); val text = itemList.selectedValue
                if (c != null && text != null) perform { repository.remove(c.id, text) }
            })
            add(button("copy_to") { val text = itemList.selectedValue ?: return@button
                val targets = repository.collections.value.filter { it.id != selectedCollection()?.id }
                val names = targets.map { if (it.kind == CollectionKind.SYSTEM) label("favorites") else "${it.name} (${it.id.take(8)})" }.toTypedArray()
                val chosen = JOptionPane.showInputDialog(this@CollectionsDialog, label("copy_to"), label("copy_to"), JOptionPane.PLAIN_MESSAGE, null, names, null) as? String
                targets.getOrNull(names.indexOf(chosen))?.let { target -> perform { repository.add(target.id, text) } }
            })
            add(button("up") { moveItem(-1) })
            add(button("down") { moveItem(1) })
        }
        val itemToolbar = JPanel(BorderLayout(0, 4)).apply {
            add(JPanel(BorderLayout(8, 0)).apply {
                add(JLabel(label("sort")), BorderLayout.LINE_START)
                add(sortBox, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(itemButtons, BorderLayout.CENTER)
        }
        sortBox.addActionListener { if (!rendering) selectedCollection()?.let { c ->
            val sort = sortBox.selectedItem as? ItemSort ?: return@let
            perform { repository.setSort(c.id, sort) }
        } }
        collectionList.addListSelectionListener { if (!it.valueIsAdjusting) renderItems() }
        val left = JPanel(BorderLayout()).apply {
            add(JScrollPane(collectionList), BorderLayout.CENTER)
            add(collectionButtons, BorderLayout.SOUTH)
        }
        val right = JPanel(BorderLayout()).apply {
            add(JScrollPane(itemList), BorderLayout.CENTER)
            add(itemToolbar, BorderLayout.SOUTH)
        }
        contentPane.add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right).apply { resizeWeight = 0.38 }, BorderLayout.CENTER)
        refresh()
    }

    private fun button(key: String, action: () -> Unit) = JButton(label(key)).apply { addActionListener { action() } }
    private fun selectedCollection() = collectionList.selectedValue
    private fun selectedUser() = selectedCollection()?.takeIf { it.kind == CollectionKind.USER }
    private fun promptName(existing: String?): String? = JOptionPane.showInputDialog(this, label("name_prompt"), existing)?.trim()?.takeIf { it.isNotEmpty() }
    private fun perform(action: suspend () -> Unit) { scope.launch {
        try { action() } catch (e: Exception) { SwingUtilities.invokeLater { JOptionPane.showMessageDialog(this@CollectionsDialog, e.message, label("title"), JOptionPane.ERROR_MESSAGE) } }
    } }
    private fun moveItem(direction: Int) { val c = selectedCollection(); val text = itemList.selectedValue
        if (c != null && text != null) perform { repository.reorderItem(c.id, text, direction) }
    }

    fun refresh() {
        val selectedId = selectedCollection()?.id
        val all = repository.collections.value
        val ordered = all.filter { it.kind == CollectionKind.SYSTEM } + all.filter { it.kind == CollectionKind.USER && it.pinned } + all.filter { it.kind == CollectionKind.USER && !it.pinned }
        listModel.clear()
        ordered.forEach(listModel::addElement)
        val index = ordered.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: 0
        if (ordered.isNotEmpty()) collectionList.selectedIndex = index
        renderItems()
    }

    private fun renderItems() {
        val c = selectedCollection()
        itemModel.clear()
        c?.sortedItems()?.forEach { itemModel.addElement(it.text) }
        rendering = true
        sortBox.selectedItem = c?.sort ?: ItemSort.NEWEST
        rendering = false
    }
}
