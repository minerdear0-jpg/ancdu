#include <errno.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/statvfs.h>

#include "csr.h"
#include "session.h"

#define FN(ret, name) JNIEXPORT ret JNICALL Java_dev_ancdu_Native_##name
#define SESS(h) ((session *)(intptr_t)(h))

static const char *const SU[] = {"su", "-c", NULL};

static jbyteArray bytes(JNIEnv *e, const char *p, size_t n) {
  jbyteArray a = (*e)->NewByteArray(e, (jsize)n);
  if (a && n) (*e)->SetByteArrayRegion(e, a, 0, (jsize)n, (const jbyte *)p);
  return a;
}

static void set_err(JNIEnv *e, jintArray err, int v) {
  if (err && (*e)->GetArrayLength(e, err) > 0) (*e)->SetIntArrayRegion(e, err, 0, 1, &v);
}

/* Путь из Kotlin — байты UTF-8 (String.toByteArray, как имена: символы вне BMP целы, без
 * modified UTF-8 GetStringUTFChars) → копия с завершающим \0 (free — вызывающий). NULL —
 * массива нет, \0 внутри (такого пути нет — отказ, не усечение) или нет памяти; *code —
 * -EINVAL или -ENOMEM. */
static char *cpath(JNIEnv *e, jbyteArray b, int *code) {
  *code = -EINVAL;
  if (!b) return NULL;
  jsize n = (*e)->GetArrayLength(e, b);
  if (n < 0) return NULL;
  char *p = malloc((size_t)n + 1);
  if (!p) {
    *code = -ENOMEM;
    return NULL;
  }
  if (n) (*e)->GetByteArrayRegion(e, b, 0, n, (jbyte *)p);
  if ((*e)->ExceptionCheck(e) || memchr(p, 0, (size_t)n)) {
    free(p);
    return NULL;
  }
  p[n] = 0;
  *code = 0;
  return p;
}

/* Арена готовой сессии и проверка узла; NULL — нельзя. */
static arena *tree(jlong h, jint node) {
  if (!h) return NULL;
  arena *a = sess_arena(SESS(h));
  if (!a || node < 0 || (uint64_t)node >= atomic_load(&a->h->count)) return NULL;
  return a;
}

FN(jlong, scanStartBytes)(JNIEnv *e, jclass c, jbyteArray root, jboolean oneFs, jint threads,
                          jintArray err) {
  (void)c;
  int code;
  char *r = cpath(e, root, &code);
  session *s = r ? sess_scan_start(r, oneFs, threads, &code) : NULL;
  free(r);
  set_err(e, err, code);
  return (jlong)(intptr_t)s;
}

FN(jlong, rootStartBytes)(JNIEnv *e, jclass c, jbyteArray helper, jbyteArray root, jboolean oneFs,
                          jboolean memfd, jintArray err) {
  (void)c;
  int code, code2;
  char *hp = cpath(e, helper, &code);
  char *r = hp ? cpath(e, root, &code2) : NULL;
  if (hp && !r) code = code2;
  session *s = r ? sess_root_start(SU, hp, r, oneFs, memfd, &code) : NULL;
  free(r);
  free(hp);
  set_err(e, err, code);
  return (jlong)(intptr_t)s;
}

FN(jlong, indexBeginBytes)(JNIEnv *e, jclass c, jbyteArray root, jlong cap, jintArray err) {
  (void)c;
  int code;
  char *r = cpath(e, root, &code);
  session *s = r ? sess_index_begin(r, (uint64_t)cap, &code) : NULL;
  free(r);
  set_err(e, err, code);
  return (jlong)(intptr_t)s;
}

/* rel и names — n строк UTF-8, каждая завершена \0. */
FN(jint, indexAdd)(JNIEnv *e, jclass c, jlong h, jbyteArray rel, jbyteArray names,
                   jlongArray sizes, jint n) {
  (void)c;
  if (!h) return -EINVAL;
  jsize rl = (*e)->GetArrayLength(e, rel), nl = (*e)->GetArrayLength(e, names);
  if ((*e)->GetArrayLength(e, sizes) < n) return -EINVAL;
  jbyte *rb = (*e)->GetByteArrayElements(e, rel, NULL);
  jbyte *nb = rb ? (*e)->GetByteArrayElements(e, names, NULL) : NULL;
  jlong *sz = nb ? (*e)->GetLongArrayElements(e, sizes, NULL) : NULL;
  if (!sz) { /* нет памяти: ничего не добавлено */
    if (nb) (*e)->ReleaseByteArrayElements(e, names, nb, JNI_ABORT);
    if (rb) (*e)->ReleaseByteArrayElements(e, rel, rb, JNI_ABORT);
    return -ENOMEM;
  }
  int result = 0;
  if (rl > 0 && nl > 0 && rb[rl - 1] == 0 && nb[nl - 1] == 0) {
    const char *rp = (const char *)rb, *np = (const char *)nb;
    for (jint i = 0; i < n && rp < (const char *)rb + rl && np < (const char *)nb + nl; i++) {
      int r = sess_index_add(SESS(h), rp, np, (uint64_t)sz[i]);
      if (r == -ENOSPC || r == -ENOMEM) { result = r; break; }
      rp += strlen(rp) + 1; /* -EINVAL (недопустимое имя) — строка пропускается */
      np += strlen(np) + 1;
    }
  } else {
    result = -EINVAL;
  }
  (*e)->ReleaseLongArrayElements(e, sizes, sz, JNI_ABORT);
  (*e)->ReleaseByteArrayElements(e, names, nb, JNI_ABORT);
  (*e)->ReleaseByteArrayElements(e, rel, rb, JNI_ABORT);
  return result;
}

FN(jint, indexFinish)(JNIEnv *e, jclass c, jlong h) {
  (void)e; (void)c;
  return h ? sess_index_finish(SESS(h)) : -EINVAL;
}

FN(jlong, openCacheBytes)(JNIEnv *e, jclass c, jbyteArray path, jintArray err) {
  (void)c;
  int code;
  char *p = cpath(e, path, &code);
  session *s = p ? sess_open_cache(p, &code) : NULL;
  free(p);
  set_err(e, err, code);
  return (jlong)(intptr_t)s;
}

FN(jint, saveCacheBytes)(JNIEnv *e, jclass c, jlong h, jbyteArray path) {
  (void)c;
  if (!h) return -EINVAL;
  int r;
  char *p = cpath(e, path, &r);
  if (p) r = sess_save_cache(SESS(h), p);
  free(p);
  return r;
}

FN(jbyteArray, progress)(JNIEnv *e, jclass c, jlong h, jlongArray out) {
  (void)c;
  char path[512] = "";
  int64_t v[6] = {ST_FAILED, 0, 0, 0, 0, 0};
  if (h) sess_progress(SESS(h), v, path, sizeof path);
  jlong jv[6];
  for (int i = 0; i < 6; i++) jv[i] = v[i];
  if ((*e)->GetArrayLength(e, out) >= 6) (*e)->SetLongArrayRegion(e, out, 0, 6, jv);
  return bytes(e, path, strlen(path));
}

FN(jint, liveTop)(JNIEnv *e, jclass c, jlong h, jintArray nodes, jlongArray disk) {
  (void)c;
  if (!h) return 0;
  int cap = (*e)->GetArrayLength(e, nodes);
  if ((*e)->GetArrayLength(e, disk) < cap) cap = (*e)->GetArrayLength(e, disk);
  if (cap > 256) cap = 256;
  uint32_t n[256];
  uint64_t d[256];
  int k = sess_live_top(SESS(h), n, d, cap);
  jint jn[256];
  jlong jd[256];
  for (int i = 0; i < k; i++) {
    jn[i] = n[i] == ANCDU_NONE ? -1 : (jint)n[i];
    jd[i] = (jlong)d[i];
  }
  (*e)->SetIntArrayRegion(e, nodes, 0, k, jn);
  (*e)->SetLongArrayRegion(e, disk, 0, k, jd);
  return k;
}

FN(jbyteArray, liveName)(JNIEnv *e, jclass c, jlong h, jint node) {
  (void)c;
  size_t n = 0;
  const char *p = (h && node > 0) ? sess_live_name(SESS(h), (uint32_t)node, &n) : NULL;
  return bytes(e, p ? p : "", p ? n : 0);
}

FN(jbyteArray, error)(JNIEnv *e, jclass c, jlong h) {
  (void)c;
  char buf[1024] = "";
  if (h) sess_error(SESS(h), buf, sizeof buf);
  return bytes(e, buf, strlen(buf));
}

FN(void, cancel)(JNIEnv *e, jclass c, jlong h) {
  (void)e; (void)c;
  if (h) sess_cancel(SESS(h));
}

FN(jint, childCount)(JNIEnv *e, jclass c, jlong h, jint node) {
  (void)e; (void)c;
  arena *a = tree(h, node);
  return a ? (jint)a->child_count[node] : 0;
}

FN(jint, children)(JNIEnv *e, jclass c, jlong h, jint node, jint sort, jboolean apparent,
                   jintArray out) {
  (void)c;
  arena *a = tree(h, node);
  if (!a) return 0;
  jsize cap = (*e)->GetArrayLength(e, out);
  if ((uint32_t)cap < a->child_count[node]) return -1; /* массив мал */
  jint *o = (*e)->GetIntArrayElements(e, out, NULL);
  if (!o) return -1;
  uint32_t k = csr_children(a, (uint32_t)node, sort, apparent, (uint32_t *)o, (uint32_t)cap);
  (*e)->ReleaseIntArrayElements(e, out, o, 0);
  return (jint)k;
}

FN(void, nodeInfo)(JNIEnv *e, jclass c, jlong h, jintArray nodes, jint n, jlongArray out) {
  (void)c;
  arena *a = tree(h, 0);
  if (!a || n <= 0 || (*e)->GetArrayLength(e, nodes) < n || (*e)->GetArrayLength(e, out) < 4 * n)
    return;
  uint64_t cnt = atomic_load(&a->h->count);
  jint *nd = (*e)->GetIntArrayElements(e, nodes, NULL);
  jlong *o = nd ? (*e)->GetLongArrayElements(e, out, NULL) : NULL;
  if (!o) {
    if (nd) (*e)->ReleaseIntArrayElements(e, nodes, nd, JNI_ABORT);
    return;
  }
  for (jint i = 0; i < n; i++) {
    jint x = nd[i];
    if (x < 0 || (uint64_t)x >= cnt) {
      o[4 * i] = o[4 * i + 1] = o[4 * i + 2] = o[4 * i + 3] = 0;
      continue;
    }
    o[4 * i] = (jlong)a->disk[x];
    o[4 * i + 1] = (jlong)a->apparent[x];
    o[4 * i + 2] = (jlong)a->items[x];
    o[4 * i + 3] = (jlong)a->flags[x];
  }
  (*e)->ReleaseLongArrayElements(e, out, o, 0);
  (*e)->ReleaseIntArrayElements(e, nodes, nd, JNI_ABORT);
}

/* Крупнейшие файлы всего дерева (csr_top_files): k = длина out (не больше 256), id узлов по
 * убыванию disk; возвращает их число (0 — нет дерева). Только чтение. */
FN(jint, topFiles)(JNIEnv *e, jclass c, jlong h, jintArray out) {
  (void)c;
  arena *a = tree(h, 0);
  if (!a || !out) return 0;
  jsize cap = (*e)->GetArrayLength(e, out);
  if (cap > 256) cap = 256;
  if (cap <= 0) return 0;
  uint32_t ids[256];
  uint32_t k = csr_top_files(a, (uint32_t)cap, ids);
  jint j[256];
  for (uint32_t i = 0; i < k; i++) j[i] = (jint)ids[i];
  if (k) (*e)->SetIntArrayRegion(e, out, 0, (jsize)k, j);
  return (jint)k;
}

FN(jbyteArray, name)(JNIEnv *e, jclass c, jlong h, jint node) {
  (void)c;
  arena *a = tree(h, node);
  if (!a) return bytes(e, "", 0);
  return bytes(e, arena_name(a, (uint32_t)node), a->name_len[node]);
}

FN(jbyteArray, path)(JNIEnv *e, jclass c, jlong h, jint node) {
  (void)c;
  arena *a = tree(h, node);
  if (!a) return bytes(e, "", 0);
  enum { CAP = 65536 };
  char *buf = malloc(CAP);
  if (!buf) return bytes(e, "", 0);
  int n = arena_path(a, (uint32_t)node, buf, CAP);
  jbyteArray r = bytes(e, buf, n > 0 ? (size_t)n : 0);
  free(buf);
  return r;
}

FN(jint, parent)(JNIEnv *e, jclass c, jlong h, jint node) {
  (void)e; (void)c;
  arena *a = tree(h, node);
  if (!a) return -1;
  uint32_t p = a->parent[node];
  return p == ANCDU_NONE ? -1 : (jint)p;
}

FN(jint, source)(JNIEnv *e, jclass c, jlong h) {
  (void)e; (void)c;
  arena *a = tree(h, 0);
  return a ? (jint)a->h->source : -1;
}

/* Узел проверяет sess_delete (а не tree()): он же сбрасывает флаг стопа при любом исходе. */
FN(jint, deleteBytes)(JNIEnv *e, jclass c, jlong h, jint node, jbyteArray helper) {
  (void)c;
  if (!h) return -EINVAL;
  if (node < 0) node = 0; /* 0 — корень, sess_delete отклонит */
  if (!helper) return sess_delete(SESS(h), (uint32_t)node, NULL, NULL);
  int code;
  char *hp = cpath(e, helper, &code);
  if (!hp) {
    /* Путь хелпера не прочитан (нет памяти, \0 внутри): ни su, ни удаления. Флаг стопа
     * сбрасывается, как при любом исходе удаления: sess_delete_media без хелпера только
     * отклоняет (-EINVAL) и сбрасывает. */
    sess_delete_media(SESS(h), (uint32_t)node, SU, NULL);
    return code;
  }
  int r = sess_delete(SESS(h), (uint32_t)node, SU, hp);
  free(hp);
  return r;
}

/* Единственные вызовы, допустимые параллельно с идущим delete на том же h:
 * трогают только атомики удаления (sess_delete_progress / sess_delete_stop). */
FN(jlong, deleteProgress)(JNIEnv *e, jclass c, jlong h) {
  (void)e; (void)c;
  return h ? (jlong)sess_delete_progress(SESS(h)) : 0;
}

FN(void, deleteStop)(JNIEnv *e, jclass c, jlong h) {
  (void)e; (void)c;
  if (h) sess_delete_stop(SESS(h));
}

/* Массовый шаг MediaStore удалил часть узла, а шаг ядра не запускался («Стоп»): узел — F_ERR.
 * На Holder.io по правилам delete. */
FN(jint, markErr)(JNIEnv *e, jclass c, jlong h, jint node) {
  (void)e; (void)c;
  if (!h || node <= 0) return -EINVAL;
  return sess_mark_err(SESS(h), (uint32_t)node);
}

/* Удаление в обход FUSE: узел /storage/emulated/<n>/X удаляется как /data/media/<n>/X под su. */
FN(jint, deleteMediaBytes)(JNIEnv *e, jclass c, jlong h, jint node, jbyteArray helper) {
  (void)c;
  if (!h) return -EINVAL;
  if (node < 0) node = 0; /* 0 — корень, sess_delete_media отклонит */
  int code;
  char *hp = cpath(e, helper, &code);
  /* hp NULL (нет хелпера, не прочитан) — sess_delete_media отклоняет -EINVAL и сбрасывает стоп */
  int r = sess_delete_media(SESS(h), (uint32_t)node, SU, hp);
  free(hp);
  return r;
}

FN(jint, statfsBytes)(JNIEnv *e, jclass c, jbyteArray path, jlongArray out) {
  (void)c;
  int code;
  char *p = cpath(e, path, &code);
  if (!p) return code;
  struct statvfs s;
  int r = statvfs(p, &s);
  int en = errno;
  free(p);
  if (r != 0) return -en;
  jlong v[3] = {(jlong)s.f_blocks * (jlong)s.f_frsize, (jlong)s.f_bfree * (jlong)s.f_frsize,
                (jlong)s.f_bavail * (jlong)s.f_frsize};
  if ((*e)->GetArrayLength(e, out) >= 3) (*e)->SetLongArrayRegion(e, out, 0, 3, v);
  return 0;
}

FN(void, free)(JNIEnv *e, jclass c, jlong h) {
  (void)e; (void)c;
  if (h) sess_free(SESS(h));
}
