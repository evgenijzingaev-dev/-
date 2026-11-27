
package ru.smartbasket.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Grants only the downloaded, fixed APK; no arbitrary file paths. */
class UpdateProvider:ContentProvider(){
    override fun onCreate()=true
    private fun file(uri:Uri):File{
        val c=context?:throw FileNotFoundException()
        if(uri.authority!=c.packageName+".updates"||uri.path!="/SmartBasket.apk"||uri.query!=null||uri.fragment!=null)throw FileNotFoundException()
        val f=File(c.cacheDir,"updates/SmartBasket.apk")
        if(!f.isFile)throw FileNotFoundException()
        return f
    }
    override fun getType(uri:Uri):String?=if(uri.path=="/SmartBasket.apk")"application/vnd.android.package-archive" else null
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor{
        if(mode!="r")throw FileNotFoundException()
        return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor{
        val f=file(uri)
        val columns=projection?.toList()?:listOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        val cursor=MatrixCursor(columns.toTypedArray())
        cursor.addRow(columns.map{when(it){OpenableColumns.DISPLAY_NAME->f.name;OpenableColumns.SIZE->f.length();else->null}}.toTypedArray())
        return cursor
    }
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException()
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
}
