package com.omgodse.notally.fragments

import com.omgodse.notally.R

class Notes : NotallyFragment() {
    // La búsqueda se abre desde la píldora inferior y no duplica icono en Toolbar.
    override fun getObservable() = model.baseNotes

    override fun getBackground() = R.drawable.notebook
}
