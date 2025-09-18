package top.stevezmt.calsync

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class AppPickerActivity : AppCompatActivity() {
	data class AppEntry(val label: String, val packageName: String, var checked: Boolean)

	private lateinit var listView: ListView
	private lateinit var saveBtn: Button
	private var showAllCheck: CheckBox? = null
	private val entries = mutableListOf<AppEntry>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_app_picker)

	listView = findViewById(R.id.list_apps)
		saveBtn = findViewById(R.id.btn_save_selection)
	showAllCheck = findViewById(R.id.cb_show_all)

	loadApps(includeAll = false)
		listView.adapter = object : BaseAdapter() {
			override fun getCount(): Int = entries.size
			override fun getItem(position: Int): Any = entries[position]
			override fun getItemId(position: Int): Long = position.toLong()
			override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
				val v = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_multiple_choice, parent, false)
				val e = entries[position]
				val cb = v.findViewById<CheckedTextView>(android.R.id.text1)
				cb.text = e.label + " (" + e.packageName + ")"
				listView.setItemChecked(position, e.checked)
				return v
			}
		}
		listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE
		listView.setOnItemClickListener { _, _, pos, _ ->
			entries[pos].checked = !entries[pos].checked
		}

		saveBtn.setOnClickListener { saveSelection() }
		showAllCheck?.setOnCheckedChangeListener { _, isChecked ->
			loadApps(includeAll = isChecked)
			(listView.adapter as BaseAdapter).notifyDataSetChanged()
		}
	}

	private fun loadApps(includeAll: Boolean) {
		val pm = packageManager
		val existing = SettingsStore.getSelectedSourceAppPkgs(this).toSet()
		val list = if (!includeAll) {
			val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply { addCategory(android.content.Intent.CATEGORY_LAUNCHER) }
			val resolved = pm.queryIntentActivities(intent, 0)
			resolved.map { ri ->
				val label = ri.loadLabel(pm).toString()
				val pkg = ri.activityInfo.packageName
				AppEntry(label, pkg, pkg in existing)
			}
		} else {
			pm.getInstalledApplications(0).map { ai ->
				val label = pm.getApplicationLabel(ai).toString()
				AppEntry(label, ai.packageName, ai.packageName in existing)
			}
		}.sortedBy { it.label.lowercase() }
		entries.clear()
		entries.addAll(list)
	}

	private fun saveSelection() {
		val selected = entries.filter { it.checked }
		SettingsStore.setSelectedSourceApps(this, selected.map { it.packageName }, selected.map { it.label })
		Toast.makeText(this, "已选择 ${selected.size} 个应用", Toast.LENGTH_SHORT).show()
		finish()
	}
}
