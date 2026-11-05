/* sososafe MIT bridge, using the MIT-licensed HevSocks5Tunnel engine. */
#include <jni.h>
#include <lwip/pbuf.h>
#include "hev-main.h"

static JNIEnv *observer_env;
static jobject observer;
static jmethodID packet_method;

/* Called synchronously on the engine's single native worker thread. */
void soso_observe(struct pbuf *p, int outbound) {
    if (!observer_env || !observer || !p || !packet_method) return;
    /* Packet data is transient. Kotlin retains only tuple/count/DNS metadata. */
    int n = p->tot_len > 4096 ? 4096 : p->tot_len;
    unsigned char data[4096];
    pbuf_copy_partial(p, data, n, 0);
    jbyteArray bytes = (*observer_env)->NewByteArray(observer_env, n);
    if (!bytes) { (*observer_env)->ExceptionClear(observer_env); return; }
    (*observer_env)->SetByteArrayRegion(observer_env, bytes, 0, n, (jbyte *)data);
    (*observer_env)->CallVoidMethod(observer_env, observer, packet_method, bytes, (jint)p->tot_len, (jboolean)outbound);
    if ((*observer_env)->ExceptionCheck(observer_env)) (*observer_env)->ExceptionClear(observer_env);
    (*observer_env)->DeleteLocalRef(observer_env, bytes);
}

JNIEXPORT jint JNICALL Java_app_sososafe_mobile_platform_vpn_NativeTunnel_run(JNIEnv *env, jclass cls, jstring config, jint fd, jobject sink) {
    const char *path = (*env)->GetStringUTFChars(env, config, 0);
    if (!path) return -1;
    observer_env = env;
    observer = (*env)->NewGlobalRef(env, sink);
    jclass sink_class = (*env)->GetObjectClass(env, sink);
    packet_method = (*env)->GetMethodID(env, sink_class, "onPacket", "([BIZ)V");
    (*env)->DeleteLocalRef(env, sink_class);
    int result = packet_method ? hev_socks5_tunnel_main(path, fd) : -1;
    (*env)->ReleaseStringUTFChars(env, config, path);
    (*env)->DeleteGlobalRef(env, observer);
    observer = 0; observer_env = 0; packet_method = 0;
    return result;
}
JNIEXPORT void JNICALL Java_app_sososafe_mobile_platform_vpn_NativeTunnel_stop(JNIEnv *env, jclass cls) { hev_socks5_tunnel_quit(); }
