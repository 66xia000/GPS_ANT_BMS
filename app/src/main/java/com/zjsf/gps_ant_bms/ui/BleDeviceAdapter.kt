package com.zjsf.gps_ant_bms.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.zjsf.gps_ant_bms.R
import com.zjsf.gps_ant_bms.model.BleDevice

class BleDeviceAdapter(
    private val context: Context,
    private val devices: List<BleDevice>,
    private val onDeviceSelected: (BleDevice) -> Unit
) : RecyclerView.Adapter<BleDeviceAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameTextView: TextView = view.findViewById(R.id.textViewDeviceName)
        val addressTextView: TextView = view.findViewById(R.id.textViewDeviceAddress)
        val rssiTextView: TextView = view.findViewById(R.id.textViewRssi)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_ble_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        holder.nameTextView.text = device.name
        holder.addressTextView.text = device.address
        holder.rssiTextView.text = "RSSI: ${device.rssi} dBm"
        
        holder.itemView.setOnClickListener {
            Toast.makeText(context, "Connecting to: ${device.address}", Toast.LENGTH_SHORT).show()
            onDeviceSelected(device)
        }
    }

    override fun getItemCount() = devices.size
}
