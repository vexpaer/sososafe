package app.sososafe.core

object Commands {
    private val pkg=Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*")
    val allowedOps=setOf("CAMERA","RECORD_AUDIO","FINE_LOCATION","COARSE_LOCATION","READ_CONTACTS","READ_CALENDAR","READ_CALL_LOG","READ_SMS","POST_NOTIFICATION","RUN_IN_BACKGROUND","RUN_ANY_IN_BACKGROUND","SYSTEM_ALERT_WINDOW","GET_USAGE_STATS")
    val modes=setOf("allow","ignore","deny","default","foreground")
    fun packageName(value:String):String { require(value.length<=255 && pkg.matches(value)) { "Invalid package name" }; return value }
    fun user(value:Int):String { require(value in 0..99999); return value.toString() }
    fun list(user:Int)=listOf("pm","list","packages","-U","-u","--user",user(user))
    fun paths(p:String,user:Int)=listOf("pm","path","--user",user(user),packageName(p))
    fun dump(p:String)=listOf("dumpsys","package",packageName(p))
    fun ops(p:String,user:Int)=listOf("cmd","appops","get","--user",user(user),packageName(p))
    fun op(p:String,op:String,user:Int):List<String>{require(op in allowedOps);return ops(p,user)+op}
    fun setOp(p:String,op:String,mode:String,user:Int):List<String> { require(op in allowedOps && mode in modes); return listOf("cmd","appops","set","--user",user(user),packageName(p),op,mode) }
    fun enabled(p:String,enable:Boolean,user:Int)=listOf("pm",if(enable) "enable" else "disable-user","--user",user(user),packageName(p))
    fun restoreEnabled(p:String,state:Int,user:Int):List<String> { require(state in 0..4); return listOf("pm",when(state){0->"default-state";1->"enable";2->"disable";3->"disable-user";else->"disable-until-used"},"--user",user(user),packageName(p)) }
    fun valid(tokens:List<String>):Boolean = runCatching {
        require(tokens.size<=10 && tokens.all { it.length<=300 && it.none { c -> c=='\u0000' || c=='\n' || c=='\r' } })
        tokens==listOf("id") || tokens==listOf("ss","-tunap") || tokens==listOf("ss","-tunape") || tokens==listOf("dumpsys","connectivity") ||
            (tokens.size==7 && tokens.take(5)==listOf("pm","list","packages","-U","-u") && tokens[5]=="--user" && tokens[6].toInt() in 0..99999) ||
            (tokens.size==5 && tokens.take(3)==listOf("pm","path","--user") && tokens[3].toInt() in 0..99999 && packageName(tokens[4]).isNotEmpty()) ||
            (tokens.size==3 && tokens.take(2)==listOf("dumpsys","package") && packageName(tokens[2]).isNotEmpty()) ||
            (tokens.size==6 && tokens.take(4)==listOf("cmd","appops","get","--user") && tokens[4].toInt() in 0..99999 && packageName(tokens[5]).isNotEmpty()) ||
            (tokens.size==7 && tokens.take(4)==listOf("cmd","appops","get","--user") && tokens[4].toInt() in 0..99999 && packageName(tokens[5]).isNotEmpty() && tokens[6] in allowedOps) ||
            (tokens.size==8 && tokens.take(4)==listOf("cmd","appops","set","--user") && tokens[4].toInt() in 0..99999 && packageName(tokens[5]).isNotEmpty() && tokens[6] in allowedOps && tokens[7] in modes) ||
            (tokens.size==5 && tokens[0]=="pm" && tokens[1] in setOf("enable","disable","disable-user","disable-until-used","default-state") && tokens[2]=="--user" && tokens[3].toInt() in 0..99999 && packageName(tokens[4]).isNotEmpty())
    }.getOrDefault(false)
    fun quote(tokens:List<String>):String { require(valid(tokens)) { "Command not in allowlist" }; return tokens.joinToString(" ") { "'"+it.replace("'","'\\''")+"'" } }
    fun apkPath(value:String):String { require(value.startsWith('/') && value.endsWith(".apk") && !value.contains("..") && value.none { it=='\n'||it=='\r'||it=='\u0000' } && (value.startsWith("/data/app/") || value.startsWith("/system/") || value.startsWith("/product/") || value.startsWith("/vendor/") || value.startsWith("/system_ext/") || value.startsWith("/apex/") || value.startsWith("/odm/"))); return value }
    fun opMode(output:String,op:String):String? = Regex("(?:^|\\s)${Regex.escape(op)}:\\s*(allow|ignore|deny|default|foreground)(?:;|\\s|$)").find(output)?.groupValues?.get(1)
    /** Only for op-specific get output. Never confuse a UID override with package state. */
    fun packageOpMode(output:String,op:String):String? {
        val direct=Regex("^${Regex.escape(op)}:\\s*(allow|ignore|deny|default|foreground)(?:;|\\s|$)",RegexOption.MULTILINE).find(output)?.groupValues?.get(1)
        return direct ?: Regex("^Default mode: (allow|ignore|deny|default|foreground)\\s*$",RegexOption.MULTILINE).find(output)?.groupValues?.get(1)
    }
}
