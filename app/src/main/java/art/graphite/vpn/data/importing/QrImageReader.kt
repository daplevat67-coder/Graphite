package art.graphite.vpn.data.importing

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

object QrImageReader {
    /** App-selected URI only, bounded input and decoded pixel allocation, no retained image. */
    fun read(context:Context,uri:Uri):String {
        val bytes=context.contentResolver.openInputStream(uri)?.use {input->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=16*1024*1024){"IMAGE_TOO_LARGE"};out.write(buffer,0,n)};out.toByteArray()
        } ?: error("IMAGE_UNAVAILABLE")
        try {
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            require(bounds.outWidth>0 && bounds.outHeight>0){"INVALID_IMAGE"}
            var sample=1;while(bounds.outWidth/sample>2048 || bounds.outHeight/sample>2048)sample*=2
            val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample}) ?: error("INVALID_IMAGE")
            try {
                val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                return try{decode(bitmap.width,bitmap.height,pixels)}finally{pixels.fill(0)}
            }finally{bitmap.recycle()}
        }finally{bytes.fill(0)}
    }
    internal fun decode(width:Int,height:Int,pixels:IntArray):String {
        val reader=QRCodeReader()
        return try{reader.decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width,height,pixels))),mapOf(DecodeHintType.TRY_HARDER to true,DecodeHintType.CHARACTER_SET to "UTF-8")).text}finally{reader.reset()}
    }
}
