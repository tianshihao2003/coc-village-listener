package cn.tsh520.cocjson

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class AppPickerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(android.R.layout.simple_list_item_1)
        setTitle(R.string.picker_title)
    }
}
