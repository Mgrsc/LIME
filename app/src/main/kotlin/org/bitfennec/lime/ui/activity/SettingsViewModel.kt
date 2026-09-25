
package org.bitfennec.lime.ui.activity

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class SettingsViewModel : ViewModel() {

    val toolbarShadow = MutableLiveData(true)

    fun enableToolbarShadow() {
        toolbarShadow.value = true
    }

    fun disableToolbarShadow() {
        toolbarShadow.value = false
    }

    override fun onCleared() {
    }
}