package org.bitfennec.lime.keyboard

import android.content.res.Configuration
import android.view.View
import androidx.emoji2.widget.EmojiTextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bitfennec.lime.R
import org.bitfennec.lime.adapter.SymbolAdapter
import org.bitfennec.lime.adapter.SymbolPagerAdapter
import org.bitfennec.lime.data.emojicon.EmojiconData
import org.bitfennec.lime.prefs.behavior.SymbolMode
import org.bitfennec.lime.view.widget.layout.SymbolGridLayoutManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdaptiveSymbolGridTest {
    @Test
    fun narrowLandscapeEmojiCellsKeepCompleteGlyphs() {
        verifyCells(SymbolMode.Emojicon, listOf("\uD83D\uDE00", "\uD83D\uDC4D", "\uD83C\uDDE8\uD83C\uDDF3"))
    }

    @Test
    fun portraitEmojiCategoryUsesMultipleColumnsFromItsFirstLayout() {
        verifyCells(SymbolMode.Emojicon, EmojiconData.emojiconData.getValue(R.drawable.icon_emojibar_smileys),
            orientation = Configuration.ORIENTATION_PORTRAIT)
    }

    @Test
    fun emojiPagerInitialBindingUsesMultipleColumns() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val data = EmojiconData.emojiconData
            val position = data.keys.indexOf(R.drawable.icon_emojibar_smileys)
            val pager = ViewPager2(context)
            pager.adapter = SymbolPagerAdapter(context, SymbolMode.Emojicon, emojiDataMap = data) { _, _ -> }
            pager.setCurrentItem(position, false)
            val density = context.resources.displayMetrics.density
            try {
                for ((widthDp, heightDp) in listOf(390 to 220, 720 to 96, 350 to 92, 390 to 220)) {
                    val width = (widthDp * density).toInt()
                    val height = (heightDp * density).toInt()
                    pager.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    pager.layout(0, 0, width, height)
                    val pages = pager.getChildAt(0) as RecyclerView
                    val page = checkNotNull(pages.findViewHolderForAdapterPosition(position))
                    val grid = page.itemView.findViewById<RecyclerView>(R.id.emojiGroupRv)
                    val manager = grid.layoutManager as GridLayoutManager
                    assertTrue("Expected multiple pager columns: width=${grid.width}, columns=${manager.spanCount}", manager.spanCount > 1)
                    assertTrue(grid.childCount > 1)
                    val first = grid.getChildAt(0)
                    val second = grid.getChildAt(1)
                    assertTrue("Stale single-column bounds: widthDp=$widthDp, columns=${manager.spanCount}, first=${first.left},${first.top},${first.width}, second=${second.left},${second.top},${second.width}",
                        first.top == second.top && first.left != second.left)
                    val maximumCellWidth = (grid.width - grid.paddingLeft - grid.paddingRight + manager.spanCount - 1) / manager.spanCount
                    assertTrue("Cell width exceeds its column: widthDp=$widthDp, cell=${first.width}, maximum=$maximumCellWidth",
                        first.width <= maximumCellWidth)
                }
            } finally {
                pager.adapter = null
            }
        }
    }

    @Test
    fun doubleCharacterSymbolsHaveEnoughWidth() {
        verifyCells(SymbolMode.Symbol, listOf("\u2026\u2026", "\u2014\u2014", "@"))
    }

    @Test
    fun commonSymbolColumnsKeepBracketPairsTogether() {
        verifyCells(SymbolMode.Symbol, EmojiconData.commonChineseSymbols, keepPairs = true)
    }

    private fun verifyCells(mode: SymbolMode, items: List<String>, keepPairs: Boolean = false,
        orientation: Int = Configuration.ORIENTATION_LANDSCAPE) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (fontScale in listOf(1f, 1.3f, 2f)) {
                val configuration = Configuration(instrumentation.targetContext.resources.configuration).apply {
                    this.fontScale = fontScale
                    this.orientation = orientation
                }
                val context = instrumentation.targetContext.createConfigurationContext(configuration)
                val density = context.resources.displayMetrics.density
                val recyclerView = RecyclerView(context)
                val manager = SymbolGridLayoutManager(context)
                recyclerView.layoutManager = manager
                val adapter = SymbolAdapter(context, mode) { _, _ -> }
                adapter.keepSymbolPairsTogether = keepPairs
                recyclerView.adapter = adapter
                try {
                    adapter.updateData(items)
                    for (width in listOf(350, 430)) {
                        val widthPx = (width * density).toInt()
                        val heightPx = (56 * density).toInt()
                        repeat(3) {
                            recyclerView.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
                            recyclerView.layout(0, 0, widthPx, heightPx)
                        }
                        assertTrue("Expected multiple columns: width=$width, fontScale=$fontScale, columns=${manager.spanCount}",
                            manager.spanCount in 2..9)
                        if (keepPairs && manager.spanCount > 1) assertEquals(0, manager.spanCount % 2)
                        assertTrue(recyclerView.childCount > 0)
                        for (index in 0 until recyclerView.childCount) {
                            val label = recyclerView.getChildAt(index).findViewById<EmojiTextView>(R.id.gv_symbols_item)
                            val layout = label.layout
                            assertTrue(layout != null && layout.lineCount > 0)
                            assertEquals(0, layout.getEllipsisCount(0))
                            assertTrue(layout.getLineWidth(0) <= label.width + 1f)
                            assertTrue(label.height <= heightPx)
                        }
                    }
                } finally {
                    recyclerView.adapter = null
                }
            }
        }
    }
}
