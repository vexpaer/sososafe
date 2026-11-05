package app.sososafe.mobile.platform;
import android.os.ParcelFileDescriptor;
interface IPrivilegedService {
    String execute(in String[] args);
    ParcelFileDescriptor openApk(String path);
    void destroy();
}
