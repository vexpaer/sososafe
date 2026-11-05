package app.sososafe.core

/** Keep every SQLite row below CursorWindow limits without splitting UTF-16 pairs. */
object JsonChunks {
    fun split(text:String,size:Int=32768):List<String>{
        require(size>=2);val result=mutableListOf<String>();var start=0
        while(start<text.length){var end=minOf(start+size,text.length);if(end<text.length && text[end-1].isHighSurrogate() && text[end].isLowSurrogate())end--;result+=text.substring(start,end);start=end}
        return result
    }
}
