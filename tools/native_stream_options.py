"""Minimal, checked adaptation of the pinned Nemotron streaming C ABI.

Model dimensions and mathematics stay upstream-owned. These additions configure
existing cache parameters and report only completed work, never a timer heartbeat.
"""


def replace_once(text, anchor, replacement):
    if text.count(anchor) != 1:
        raise RuntimeError("Pinned Nemotron adaptation anchor changed: " + anchor[:90])
    return text.replace(anchor, replacement, 1)


def adapt_header(text):
    anchor = "int nemotron3_diar_n_speakers(struct nemotron3_diar_context* ctx);\n"
    return replace_once(text, anchor, anchor + """
// Utterlane v1: configure a fresh context before stream_begin; dimensions stay fixed.
int utterlane_n3d_options_v1(struct nemotron3_diar_context* ctx, int cache, int fifo, int update);
// Called synchronously only AFTER a feature/transformer/cache stage completes.
typedef void (*utterlane_n3d_work_callback)(void* user, int stage);
void utterlane_n3d_set_work_callback(struct nemotron3_diar_context* ctx,
    utterlane_n3d_work_callback callback, void* user);
""")


def adapt_source(text):
    anchor = "struct nemotron3_diar_context {\n"
    text = replace_once(text, anchor, anchor + """    utterlane_n3d_work_callback work_callback = nullptr;
    void* work_user = nullptr;
""")
    anchor = 'extern "C" nemotron3_diar_stream* nemotron3_diar_stream_begin('
    text = replace_once(text, anchor, """extern "C" int utterlane_n3d_options_v1(nemotron3_diar_context* c, int cache, int fifo, int update) {
    if (!c || cache < 16 || cache > 1024 || cache % 8 || fifo < 16 || fifo > 1024 ||
        update < 1 || update > fifo) return -1;
    c->hp.cache_len = cache;
    c->hp.s_fifo_len = fifo;
    c->hp.s_update_period = update;
    return 0;
}

extern "C" void utterlane_n3d_set_work_callback(nemotron3_diar_context* c,
        utterlane_n3d_work_callback callback, void* user) {
    if (c) { c->work_callback = callback; c->work_user = user; }
}

""" + anchor)
    anchor = "    if (!embed(c, mel, T_chunk, emb, Ne))\n        return false;\n"
    text = replace_once(text, anchor, anchor + "    if (c->work_callback) c->work_callback(c->work_user, 0);\n")
    anchor = "    if (!run_chunk(c, x_rows, N, mask, lg))\n        return false;\n"
    text = replace_once(text, anchor, anchor + "    if (c->work_callback) c->work_callback(c->work_user, 1);\n")
    anchor = "    st->cache.update(x_rows, N, lg, scored, mask, c->sil_host);\n"
    return replace_once(text, anchor, anchor + "    if (c->work_callback) c->work_callback(c->work_user, 2);\n")
