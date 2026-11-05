package app.sososafe.mobile.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import app.sososafe.mobile.data.UserSettings
import java.util.Locale

val LocalChinese=staticCompositionLocalOf{false}
@Composable fun t(en:String,zh:String)=if(LocalChinese.current)zh else en
@Composable fun SosoTheme(settings:UserSettings,content:@Composable ()->Unit){
    val dark=when(settings.theme){"dark"->true;"light"->false;else->isSystemInDarkTheme()}
    val primary=when(settings.color){"blue"->Color(0xFF315DA8);"plum"->Color(0xFF81507C);else->Color(0xFF006B5B)}
    val color=if(dark) Color(0xFF8CD8C2) else primary
    val scheme=if(settings.dynamic && Build.VERSION.SDK_INT>=31){if(dark)dynamicDarkColorScheme(LocalContext.current)else dynamicLightColorScheme(LocalContext.current)}else if(dark)darkColorScheme(primary=color,secondary=Color(0xFFB3CCC4))else lightColorScheme(primary=primary,secondary=Color(0xFF4B635C))
    val chinese=when(settings.language){"zh"->true;"en"->false;else->LocalConfiguration.current.locales[0].language=="zh"}
    CompositionLocalProvider(LocalChinese provides chinese){MaterialTheme(colorScheme=scheme,content=content)}
}
