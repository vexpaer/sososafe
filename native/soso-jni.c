/* sososafe MIT bridge using the MIT-licensed HevSocks5Tunnel engine.
 * Hev runs on fiber stacks: never enter ART/JNI from a packet callback.
 * A bounded native queue is drained by a regular JVM worker instead. */
#include <jni.h>
#include <stdatomic.h>
#include <pthread.h>
#include <string.h>
#include <lwip/pbuf.h>
#include "hev-main.h"

#define QUEUE_SIZE 256
#define CAPTURE_SIZE 4096
typedef struct {
    int size;
    int wire_length;
    int outbound;
    unsigned char data[CAPTURE_SIZE];
} Packet;
static Packet queue[QUEUE_SIZE];
static int head, count;
static unsigned long dropped;
static jlong session;
static atomic_int active;
static pthread_mutex_t queue_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t drain_mutex = PTHREAD_MUTEX_INITIALIZER;

void soso_observe(struct pbuf *p, int outbound) {
    if (!p) return;
    pthread_mutex_lock(&queue_mutex);
    if (count == QUEUE_SIZE) dropped++;
    else {
        Packet *slot = &queue[(head + count) % QUEUE_SIZE];
        slot->size = p->tot_len > CAPTURE_SIZE ? CAPTURE_SIZE : p->tot_len;
        slot->wire_length = p->tot_len;
        slot->outbound = outbound;
        pbuf_copy_partial(p, slot->data, slot->size, 0);
        count++;
    }
    pthread_mutex_unlock(&queue_mutex);
}

JNIEXPORT jint JNICALL Java_app_sososafe_mobile_platform_vpn_NativeTunnel_run(JNIEnv *env, jclass cls, jstring config, jint fd, jlong token) {
    int expected = 0;
    if (!atomic_compare_exchange_strong(&active, &expected, 1)) return -2;
    const char *path = (*env)->GetStringUTFChars(env, config, 0);
    if (!path) { atomic_store(&active, 0); return -1; }
    pthread_mutex_lock(&queue_mutex);
    memset(queue, 0, sizeof(queue));
    head = count = 0; dropped = 0; session = token;
    pthread_mutex_unlock(&queue_mutex);
    int result = hev_socks5_tunnel_main(path, fd);
    (*env)->ReleaseStringUTFChars(env, config, path);
    atomic_store(&active, 0);
    return result;
}

JNIEXPORT jlong JNICALL Java_app_sososafe_mobile_platform_vpn_NativeTunnel_drain(JNIEnv *env, jclass cls, jobject sink, jlong token) {
    unsigned long lost = 0;
    pthread_mutex_lock(&drain_mutex);
    jclass sink_class = (*env)->GetObjectClass(env, sink);
    jmethodID method = (*env)->GetMethodID(env, sink_class, "onPacket", "([BIZ)V");
    (*env)->DeleteLocalRef(env, sink_class);
    if (!method) { (*env)->ExceptionClear(env); pthread_mutex_unlock(&drain_mutex); return 0; }
    for (int i = 0; i < QUEUE_SIZE; i++) {
        Packet packet;
        pthread_mutex_lock(&queue_mutex);
        if (session != token) { pthread_mutex_unlock(&queue_mutex); break; }
        lost += dropped; dropped = 0;
        if (!count) { pthread_mutex_unlock(&queue_mutex); break; }
        packet = queue[head];
        memset(&queue[head], 0, sizeof(Packet));
        head = (head + 1) % QUEUE_SIZE; count--;
        pthread_mutex_unlock(&queue_mutex);
        jbyteArray bytes = (*env)->NewByteArray(env, packet.size);
        if (!bytes) { (*env)->ExceptionClear(env); lost++; continue; }
        (*env)->SetByteArrayRegion(env, bytes, 0, packet.size, (jbyte *)packet.data);
        (*env)->CallVoidMethod(env, sink, method, bytes, (jint)packet.wire_length, (jboolean)packet.outbound);
        if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); lost++; }
        (*env)->DeleteLocalRef(env, bytes);
        memset(&packet, 0, sizeof(packet));
    }
    pthread_mutex_unlock(&drain_mutex);
    return (jlong)lost;
}

JNIEXPORT void JNICALL Java_app_sososafe_mobile_platform_vpn_NativeTunnel_stop(JNIEnv *env, jclass cls) { hev_socks5_tunnel_quit(); }
