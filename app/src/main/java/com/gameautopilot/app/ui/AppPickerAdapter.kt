package com.gameautopilot.app.ui

import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.gameautopilot.app.R

class AppPickerAdapter(
    private val pm: PackageManager,
    private val onPick: (label: String, pkg: String) -> Unit
) : RecyclerView.Adapter<AppPickerAdapter.VH>() {

    private val items = mutableListOf<ResolveInfo>()

    fun submit(list: List<ResolveInfo>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app_picker, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ri = items[position]
        val label = ri.loadLabel(pm)?.toString().orEmpty()
        val pkg = ri.activityInfo.packageName
        holder.name.text = label
        holder.pkg.text = pkg
        holder.icon.setImageDrawable(ri.loadIcon(pm))
        holder.itemView.setOnClickListener { onPick(label, pkg) }
    }

    override fun getItemCount(): Int = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.appIcon)
        val name: TextView = v.findViewById(R.id.appName)
        val pkg: TextView = v.findViewById(R.id.appPackage)
    }
}
