package com.gameautopilot.app.ui

import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.gameautopilot.app.R
import com.gameautopilot.app.data.Game
import com.google.android.material.button.MaterialButton

class GameListAdapter(
    private val packageManager: PackageManager,
    private val onLaunch: (Game) -> Unit,
    private val onEdit: (Game) -> Unit
) : RecyclerView.Adapter<GameListAdapter.VH>() {

    private val items = mutableListOf<Game>()

    fun submit(newItems: List<Game>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_game, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val g = items[position]
        holder.name.text = g.name
        holder.pkg.text = g.packageName
        holder.icon.setImageDrawable(
            runCatching { packageManager.getApplicationIcon(g.packageName) }
                .getOrElse { holder.itemView.context.getDrawable(R.mipmap.ic_launcher) }
        )
        holder.launch.setOnClickListener { onLaunch(g) }
        holder.itemView.setOnClickListener { onEdit(g) }
    }

    override fun getItemCount(): Int = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.gameIcon)
        val name: TextView = v.findViewById(R.id.gameName)
        val pkg: TextView = v.findViewById(R.id.gamePackage)
        val launch: MaterialButton = v.findViewById(R.id.launchBtn)
    }
}
