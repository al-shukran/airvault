/*
 * JNI wrapper for BrotliEncoder.encode(input, quality, lgwin): one-shot Brotli compression with Google's encoder.
 * Throws IllegalStateException when the encoder refuses the settings or the input.
 */
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>

#include <jni.h>

#include <brotli/encode.h>

JNIEXPORT jbyteArray JNICALL
Java_app_lensly_app_export_BrotliEncoder_encode(JNIEnv *env, jobject thiz, jbyteArray input, jint quality, jint lgwin) {
    (void)thiz;
    jsize input_size = (*env)->GetArrayLength(env, input);
    jbyte *in = (*env)->GetByteArrayElements(env, input, NULL);
    if (in == NULL) {
        return NULL; /* OutOfMemoryError is already pending. */
    }
    size_t capacity = BrotliEncoderMaxCompressedSize((size_t)input_size);
    uint8_t *out = (uint8_t *)malloc(capacity);
    if (out == NULL) {
        (*env)->ReleaseByteArrayElements(env, input, in, JNI_ABORT);
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/OutOfMemoryError"), "Brotli output buffer");
        return NULL;
    }
    size_t encoded_size = capacity;
    BROTLI_BOOL ok = BrotliEncoderCompress(quality, lgwin, BROTLI_MODE_GENERIC, (size_t)input_size,
                                           (const uint8_t *)in, &encoded_size, out);
    (*env)->ReleaseByteArrayElements(env, input, in, JNI_ABORT);
    if (!ok) {
        free(out);
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), "Brotli could not encode the input");
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize)encoded_size);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)encoded_size, (const jbyte *)out);
    }
    free(out);
    return result;
}
