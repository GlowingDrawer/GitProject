package com.example.cgmdemo

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SensorAdapter : RecyclerView.Adapter<SensorAdapter.VH>() {

    private val items = mutableListOf<SensorRecord>()

    fun submitList(list: List<SensorRecord>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val textView: TextView) : RecyclerView.ViewHolder(textView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        holder.textView.text =
            "t=${"%.3f".format(r.seconds)}s, " +
                    "Uric=${"%.3f".format(r.uric)}uA, " +
                    "Asc=${"%.3f".format(r.ascorbic)}uA, " +
                    "Glu=${"%.3f".format(r.glucose)}mA, " +
                    "V=${"%.3f".format(r.voltage)}V, " +
                    "time=${r.receiveTime}"
    }

    override fun getItemCount(): Int = items.size
}
