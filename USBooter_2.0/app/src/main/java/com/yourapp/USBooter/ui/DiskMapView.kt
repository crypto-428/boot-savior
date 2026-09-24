package com.yourapp.USBooter.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.textview.MaterialTextView
import com.yourapp.USBooter.util.PartitionDefinition

/**
 * Fills [container] (a horizontal LinearLayout) with one proportionally-weighted
 * segment per partition, drawn to scale against the drive's total capacity -
 * a literal to-scale map of the disk, not just a list of partition cards.
 */
object DiskMapView {

    fun populate(container: LinearLayout, partitions: List<PartitionDefinition>, totalBytes: Long) {
        container.removeAllViews()
        if (partitions.isEmpty()) return

        val context = container.context
        val fixedMB = partitions.filter { it.sizeMB > 0 }.sumOf { it.sizeMB }
        val totalMB = (totalBytes / 1_000_000).coerceAtLeast(1)
        val fillMB = (totalMB - fixedMB).coerceAtLeast(1)

        // Pure byte-proportional width makes small partitions (e.g. a 500MB ESP next
        // to a 15GB data partition) render a couple of pixels wide with unreadable
        // wrapped-letter-by-letter text. Floor every segment at a minimum share of
        // the total, same trade-off the original mockup made by hand.
        val rawWeights = partitions.map { if (it.sizeMB == -1) fillMB.toFloat() else it.sizeMB.toFloat() }
        val totalWeight = rawWeights.sum().coerceAtLeast(1f)
        val minWeight = totalWeight * 0.15f

        partitions.forEachIndexed { index, partition ->
            if (index > 0) {
                container.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(2, LinearLayout.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(themeColor(context, com.google.android.material.R.attr.colorOutline))
                })
            }

            val weight = rawWeights[index].coerceAtLeast(minWeight)
            val segment = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(4, 0, 4, 0)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, weight
                )
                setBackgroundColor(
                    if (partition.isESP)
                        themeColor(context, android.R.attr.colorPrimary)
                    else
                        themeColor(context, com.google.android.material.R.attr.colorSurfaceVariant)
                )
            }

            val onSegmentColor = if (partition.isESP)
                themeColor(context, com.google.android.material.R.attr.colorOnPrimary)
            else
                themeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant)

            segment.addView(MaterialTextView(context).apply {
                text = partition.label
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(onSegmentColor)
                gravity = Gravity.CENTER
                maxLines = 1
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            segment.addView(MaterialTextView(context).apply {
                text = partition.filesystem.displayName
                textSize = 8f
                alpha = 0.85f
                setTextColor(onSegmentColor)
                gravity = Gravity.CENTER
                maxLines = 1
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            })

            container.addView(segment)
        }
    }

    private fun themeColor(context: Context, attr: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }
}
