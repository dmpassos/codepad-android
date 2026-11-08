/* CodePad — ponte nativa (Capacitor / Android)
 * Só se ativa dentro da app Android; no browser normal não faz nada.
 *   1. Exportações (Blob + <a download>)  → guarda em cache e abre a folha "Partilhar"
 *   2. navigator.clipboard (página + iframe do PseudoPad) → plugin Clipboard
 *   3. navigator.vibrate                  → plugin Haptics
 *   4. Botão voltar                       → history.back(); na raiz pede 2 toques para sair
 *   5. Pasta local (Workspace)            → showDirectoryPicker() sobre o plugin Kotlin LocalFolder
 */
(function () {
  "use strict";
  var cap = window.Capacitor;
  if (!cap || typeof cap.isNativePlatform !== "function" || !cap.isNativePlatform()) return;
  var P = cap.Plugins || {};

  /* ── aviso curto (usa o showToast do CodePad se existir) ─────────────────── */
  function toast(msg, kind) {
    try {
      if (typeof window.showToast === "function") return void window.showToast(msg, kind || "");
    } catch (e) {}
    try {
      var d = document.createElement("div");
      d.textContent = msg;
      d.style.cssText =
        "position:fixed;left:50%;bottom:90px;transform:translateX(-50%);z-index:99999;" +
        "background:#111827;color:#e2eaf5;padding:10px 16px;border-radius:10px;font:14px system-ui;" +
        "max-width:80vw;text-align:center;box-shadow:0 4px 20px rgba(0,0,0,.5)";
      document.body.appendChild(d);
      setTimeout(function () { d.remove(); }, 2200);
    } catch (e) {}
  }

  /* ── 1. Exportar ficheiros ───────────────────────────────────────────────── */
  function blobToBase64(blob) {
    return new Promise(function (res, rej) {
      var r = new FileReader();
      r.onload = function () { var s = String(r.result); res(s.slice(s.indexOf(",") + 1)); };
      r.onerror = function () { rej(r.error); };
      r.readAsDataURL(blob);
    });
  }
  function safeName(n) {
    return String(n || "ficheiro").replace(/[\\\/:*?"<>|\x00-\x1f]+/g, "_").slice(0, 120) || "ficheiro";
  }
  function saveAndShare(blob, name) {
    if (!P.Filesystem || !P.Share) { toast("Exportar indisponível nesta versão"); return Promise.resolve(); }
    return blobToBase64(blob)
      .then(function (b64) {
        return P.Filesystem.writeFile({
          path: "exports/" + Date.now() + "/" + safeName(name),
          data: b64,
          directory: "CACHE",
          recursive: true,
        });
      })
      .then(function (r) {
        return P.Share.share({ title: name, dialogTitle: "Guardar / partilhar", url: r.uri });
      })
      .catch(function (e) {
        var m = (e && (e.message || e.errorMessage)) || "";
        if (/cancel/i.test(m)) return; /* o utilizador fechou a folha de partilha */
        toast("Falha ao exportar: " + (m || "erro desconhecido"));
      });
  }

  /* guarda cada Blob criado, para o ler no clique sem depender do blob: URL */
  var blobs = new Map();
  var _create = URL.createObjectURL.bind(URL);
  var _revoke = URL.revokeObjectURL.bind(URL);
  URL.createObjectURL = function (obj) {
    var u = _create(obj);
    if (typeof Blob !== "undefined" && obj instanceof Blob) blobs.set(u, obj);
    return u;
  };
  URL.revokeObjectURL = function (u) { blobs.delete(u); return _revoke(u); };

  var _click = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    try {
      if (this.hasAttribute("download") && /^blob:/.test(this.href)) {
        var blob = blobs.get(this.href);
        if (blob) { saveAndShare(blob, this.getAttribute("download")); return; }
      }
    } catch (e) {}
    return _click.apply(this, arguments);
  };

  /* limpa exportações antigas da cache a cada arranque */
  if (P.Filesystem) {
    try { P.Filesystem.rmdir({ path: "exports", directory: "CACHE", recursive: true }).catch(function () {}); } catch (e) {}
  }

  /* API opcional para o resto do código:  CodePadNative.saveFile("x.c", "texto…") */
  window.CodePadNative = {
    saveFile: function (name, data, mime) {
      var blob = data instanceof Blob ? data : new Blob([data], { type: mime || "application/octet-stream" });
      return saveAndShare(blob, name);
    },
  };

  /* ── 2. Área de transferência ────────────────────────────────────────────── */
  if (P.Clipboard) {
    var clip = {
      writeText: function (t) { return P.Clipboard.write({ string: String(t == null ? "" : t) }); },
      readText: function () {
        return P.Clipboard.read().then(function (r) { return (r && r.value) || ""; });
      },
    };
    var installClip = function (win) {
      try { Object.defineProperty(win.navigator, "clipboard", { configurable: true, value: clip }); } catch (e) {}
    };
    installClip(window);

    /* o PseudoPad vive numa iframe (srcdoc) criada mais tarde: aplicar-lhe o mesmo */
    var hookFrame = function (f) {
      var go = function () { try { if (f.contentWindow) installClip(f.contentWindow); } catch (e) {} };
      f.addEventListener("load", go);
      go();
    };
    new MutationObserver(function (list) {
      list.forEach(function (m) {
        m.addedNodes.forEach(function (n) { if (n.tagName === "IFRAME") hookFrame(n); });
      });
    }).observe(document.documentElement, { childList: true, subtree: true });
  }

  /* ── 3. Vibração ─────────────────────────────────────────────────────────── */
  if (P.Haptics) {
    try {
      Object.defineProperty(navigator, "vibrate", {
        configurable: true,
        value: function (p) {
          var ms = Array.isArray(p) ? p[0] : p;
          ms = Number(ms) || 0;
          if (ms <= 0) return true;
          var job = ms <= 12 ? P.Haptics.impact({ style: "LIGHT" }) : P.Haptics.vibrate({ duration: ms });
          if (job && job.catch) job.catch(function () {});
          return true;
        },
      });
    } catch (e) {}
  }

  /* ── 4. Botão voltar ─────────────────────────────────────────────────────── */
  if (P.App) {
    var lastBack = 0;
    P.App.addListener("backButton", function (ev) {
      if (ev && ev.canGoBack) { history.back(); return; } /* o CodePad usa pushState para Output / Menu / Biblioteca */
      var now = Date.now();
      if (now - lastBack < 2000) { P.App.exitApp(); return; }
      lastBack = now;
      toast("Toque em voltar de novo para sair");
    });
  }

  /* ── 5. Pasta local → File System Access API (plugin Kotlin "LocalFolder") ─────
   * O Workspace do CodePad usa window.showDirectoryPicker(), que o WebView do
   * Android não tem. Aqui criamo-lo em cima do plugin nativo (Storage Access
   * Framework), para o HTML funcionar sem alterações. */
  if (P.LocalFolder) {
    var LF = P.LocalFolder;

    var domErr = function (name, msg) {
      try { return new DOMException(msg, name); } catch (e) { var er = new Error(msg); er.name = name; return er; }
    };
    var mapErr = function (e) {
      var code = e && e.code;
      var msg = (e && (e.message || e.errorMessage)) || "Erro ao aceder à pasta";
      if (code === "CANCELLED") return domErr("AbortError", msg);
      if (code === "NOT_FOUND") return domErr("NotFoundError", msg);
      if (code === "TYPE_MISMATCH") return domErr("TypeMismatchError", msg);
      if (code === "NOT_EMPTY") return domErr("InvalidModificationError", msg);
      if (code === "PERMISSION") return domErr("NotAllowedError", msg);
      var er = new Error(msg); er.code = code; return er;
    };
    var nat = function (method, args) {
      return LF[method](args || {}).catch(function (e) { throw mapErr(e); });
    };
    var joinPath = function (base, name) { return base ? base + "/" + name : name; };
    var checkName = function (name) {
      if (typeof name !== "string" || !name || name === "." || name === ".." || /[\/\\]/.test(name)) {
        throw new TypeError("Nome inválido: " + name);
      }
    };

    class NativeHandle {
      constructor(kind, tree, path, name) { this.kind = kind; this.name = name; this._t = tree; this._p = path; }
      async isSameEntry(o) { return !!o && o._t === this._t && o._p === this._p; }
    }

    class NativeFile {
      constructor(h, m) {
        this.name = h.name; this.size = m.size || 0; this.lastModified = m.lastModified || 0; this.type = ""; this._h = h;
      }
      async text() { return (await nat("readText", { tree: this._h._t, path: this._h._p })).text; }
      async arrayBuffer() { return new TextEncoder().encode(await this.text()).buffer; } /* só texto */
    }

    class NativeWritable {
      constructor(h, initial) { this._h = h; this._buf = initial || ""; this._done = false; }
      async write(data) {
        if (this._done) throw domErr("InvalidStateError", "O ficheiro já foi fechado");
        if (data && typeof data === "object" && data.type === "write") data = data.data;
        if (typeof Blob !== "undefined" && data instanceof Blob) data = await data.text();
        else if (data instanceof ArrayBuffer || ArrayBuffer.isView(data)) data = new TextDecoder().decode(data);
        this._buf += String(data);
      }
      async truncate(size) { this._buf = this._buf.slice(0, size); }
      async close() {
        if (this._done) return;
        this._done = true;
        await nat("writeText", { tree: this._h._t, path: this._h._p, text: this._buf, create: true });
        this._h._meta = null;
      }
      async abort() { this._done = true; }
    }

    class NativeFileHandle extends NativeHandle {
      constructor(tree, path, name, meta) {
        super("file", tree, path, name);
        this._meta = meta ? { m: meta, t: Date.now() } : null; /* evita um 2.º pedido nativo logo a seguir */
      }
      async getFile() {
        var m = this._meta && Date.now() - this._meta.t < 1500 ? this._meta.m : null;
        if (!m) {
          var r = await nat("stat", { tree: this._t, path: this._p });
          if (!r.exists) throw domErr("NotFoundError", 'O ficheiro "' + this.name + '" não existe');
          if (r.kind !== "file") throw domErr("TypeMismatchError", '"' + this.name + '" é uma pasta');
          m = r;
        }
        return new NativeFile(this, m);
      }
      async createWritable(opts) {
        var initial = "";
        if (opts && opts.keepExistingData) initial = await (await this.getFile()).text();
        return new NativeWritable(this, initial);
      }
    }

    class NativeDirHandle extends NativeHandle {
      constructor(tree, path, name) { super("directory", tree, path, name); }
      async queryPermission() {
        return (await nat("hasPermission", { tree: this._t })).granted ? "granted" : "prompt";
      }
      async requestPermission() {
        return (await nat("hasPermission", { tree: this._t })).granted ? "granted" : "denied";
      }
      async getDirectoryHandle(name, opts) {
        checkName(name);
        var p = joinPath(this._p, name);
        var r = await nat("stat", { tree: this._t, path: p });
        if (r.exists) {
          if (r.kind !== "directory") throw domErr("TypeMismatchError", '"' + name + '" é um ficheiro');
          return new NativeDirHandle(this._t, p, name);
        }
        if (!(opts && opts.create)) throw domErr("NotFoundError", 'A pasta "' + name + '" não existe');
        await nat("mkdir", { tree: this._t, path: p });
        return new NativeDirHandle(this._t, p, name);
      }
      async getFileHandle(name, opts) {
        checkName(name);
        var p = joinPath(this._p, name);
        var r = await nat("stat", { tree: this._t, path: p });
        if (r.exists) {
          if (r.kind !== "file") throw domErr("TypeMismatchError", '"' + name + '" é uma pasta');
          return new NativeFileHandle(this._t, p, name, r);
        }
        if (!(opts && opts.create)) throw domErr("NotFoundError", 'O ficheiro "' + name + '" não existe');
        await nat("writeText", { tree: this._t, path: p, text: "", create: true });
        return new NativeFileHandle(this._t, p, name, null);
      }
      async removeEntry(name, opts) {
        checkName(name);
        await nat("remove", { tree: this._t, path: joinPath(this._p, name), recursive: !!(opts && opts.recursive) });
      }
      async *entries() {
        var r = await nat("list", { tree: this._t, path: this._p });
        for (var i = 0; i < r.entries.length; i++) {
          var e = r.entries[i], p = joinPath(this._p, e.name);
          yield [e.name, e.kind === "directory" ? new NativeDirHandle(this._t, p, e.name) : new NativeFileHandle(this._t, p, e.name, e)];
        }
      }
      async *keys() { for await (var kv of this.entries()) yield kv[0]; }
      async *values() { for await (var kv of this.entries()) yield kv[1]; }
      [Symbol.asyncIterator]() { return this.entries(); }
    }

    Object.defineProperty(window, "showDirectoryPicker", {
      configurable: true,
      writable: true,
      value: async function () {
        var args = {};
        try { var last = localStorage.getItem("cp_lastTree"); if (last) args.initialUri = last; } catch (e) {}
        var r = await nat("pickDirectory", args); /* cancelar → AbortError, como no Chrome */
        try { localStorage.setItem("cp_lastTree", r.tree); } catch (e) {}
        return new NativeDirHandle(r.tree, "", r.name);
      },
    });

    document.addEventListener("DOMContentLoaded", function () {
      var b = document.getElementById("mapLocalFolder");
      if (b) b.title = "Mapear uma pasta do aparelho: os .c/.h são lidos e gravados diretamente nela";
    });
  }
})();
