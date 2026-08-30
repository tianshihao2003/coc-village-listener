package cn.tsh520.cocjson

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cn.tsh520.cocjson.logic.TargetAppLauncher
import cn.tsh520.cocjson.logic.TargetAppStore

class AppPickerActivity : AppCompatActivity() {

    private var all: List<Pair<String, String>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_picker)
        all = TargetAppLauncher.launchableApps(this)

        val adapter = Adapter()
        findViewById<RecyclerView>(R.id.list).layoutManager = LinearLayoutManager(this)
        findViewById<RecyclerView>(R.id.list).adapter = adapter
        adapter.submit(all)

        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                adapter.submit(all.filter { it.second.contains(s ?: "", true) })
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        private var items: List<Pair<String, String>> = emptyList()
        fun submit(list: List<Pair<String, String>>) { items = list; notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, vt: Int) =
            VH(layoutInflater.inflate(R.layout.item_app, parent, false) as TextView)
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val (pkg, label) = items[pos]
            h.name.text = "$label（$pkg）"
            h.name.setOnClickListener { TargetAppStore.setTarget(this@AppPickerActivity, pkg, label); finish() }
        }
    }

    private class VH(val name: TextView) : RecyclerView.ViewHolder(name)
}
