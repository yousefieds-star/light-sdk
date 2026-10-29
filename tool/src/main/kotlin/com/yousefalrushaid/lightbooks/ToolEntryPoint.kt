package com.yousefalrushaid.lightbooks

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.toolmanager.ClientLeafNode
import com.thelightphone.toolmanager.ClientToolManifest
import com.thelightphone.toolmanager.FileBrowserSpec

/** Folder (inside the tool's shared files) that the Tool Manager uploads books into. */
const val BOOKS_DIR = "books"

@EntryPoint
object ToolEntryPoint : LightEntryPoint {

    override fun getToolManagerManifest(): ClientToolManifest {
        return ClientToolManifest(
            title = "Books",
            roots = listOf(
                ClientLeafNode(
                    FileBrowserSpec(
                        label = "Books",
                        path = BOOKS_DIR,
                        headerText = "Upload EPUB, PDF or TXT files. They appear in Books on your Light Phone.",
                    )
                )
            )
        )
    }

    // Runs in the background whenever files change through the Tool Manager.
    override suspend fun onToolManagerDataUpdate() {
        super.onToolManagerDataUpdate()
        // If Books is open, prepare the new books right away so they show up and open fast.
        BookStore.current?.scan()
        BookStore.notifyChanged()
    }
}
