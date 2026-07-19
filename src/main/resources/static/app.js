/* =========================================================================
 * ephemeral — frontend controller (vanilla JS, no framework, no build step)
 *
 * Layers, top to bottom:
 *   1.  State & small utilities (escaping, time, DOM helper, toasts)
 *   2.  API layer      — thin fetch() wrapper over the REST contract
 *   3.  WebSocket layer — realtime messages / typing, reconnect w/ backoff
 *   4.  LiveKit layer  — voice / video / screen share
 *   5.  Render layer    — pure-ish DOM builders for each surface
 *   6.  Actions         — user intents (send, save, delete, join, …)
 *   7.  Event wiring    — bind DOM + bootstrap
 *
 * All server/user text is escaped before it reaches innerHTML. Most DOM is
 * built with the `h()` helper (textContent-based, injection-safe); the one
 * place we use innerHTML is message content, which goes through escape()+link().
 * ========================================================================= */
(function () {
  "use strict";

  /* =======================================================================
   * 1. STATE & UTILITIES
   * ===================================================================== */

  const state = {
    token: localStorage.getItem("ephemeral_token") || null,
    me: null,                    // {id, username, displayName}
    guilds: [],                  // [Guild]
    currentGuild: null,          // Guild
    currentChannel: null,        // Channel
    messages: [],                // messages for current channel, ASCENDING
    hasMoreOlder: false,         // more history to page in?
    members: [],                 // members of current guild
    myRole: "member",            // my role in current guild
    typing: new Map(),           // userId -> {name, timeout}
    authMode: "login",           // 'login' | 'register'
    loadingOlder: false,
    voicePresence: {},           // channelId -> [{userId, name}] currently in each voice channel
    presence: {},                // userId -> {online, status, customStatus}
    replyingTo: null,            // {id, authorName, content} — pending reply target
    editingId: null,             // message id currently being inline-edited
    readState: {},               // channelId -> {channelId, mentionCount, lastReadId, latestId}
    newDivider: null,            // lastReadId boundary for the "New messages" divider in the open channel
    draftMentions: {},           // displayName -> userId for the current composer draft
    dmMode: false,               // are we in the Direct Messages "space"?
    dms: [],                     // [DmDto] my DM conversations
    currentDm: null,             // the open DM (DmDto)
  };

  // Element lookup shortcut.
  const $ = (id) => document.getElementById(id);

  /**
   * Tiny hyperscript DOM builder.
   *   h('div', {class:'x', onclick:fn, dataset:{id:1}}, 'text', childEl)
   * Special attrs: class, text (textContent), html (innerHTML), dataset,
   * on* (event listeners). Everything else -> setAttribute.
   */
  function h(tag, attrs, ...children) {
    const el = document.createElement(tag);
    if (attrs) {
      for (const [k, v] of Object.entries(attrs)) {
        if (v == null || v === false) continue;
        if (k === "class") el.className = v;
        else if (k === "text") el.textContent = v;
        else if (k === "html") el.innerHTML = v;
        else if (k === "dataset") Object.assign(el.dataset, v);
        else if (k.startsWith("on") && typeof v === "function")
          el.addEventListener(k.slice(2).toLowerCase(), v);
        else el.setAttribute(k, v);
      }
    }
    for (const c of children.flat()) {
      if (c == null || c === false) continue;
      el.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
    }
    return el;
  }

  // HTML-escape untrusted text.
  function escape(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])
    );
  }

  // Turn bare http(s) URLs (already-escaped text) into links.
  function link(escaped) {
    return escaped.replace(
      /(https?:\/\/[^\s<]+[^\s<.,;:!?)])/g,
      (u) => `<a href="${u}" target="_blank" rel="noopener noreferrer">${u}</a>`
    );
  }

  function escapeRegex(s) { return String(s).replace(/[.*+?^${}()|[\]\\]/g, "\\$&"); }

  // Resolve a userId to a display name via the current guild's members (or me).
  function memberName(userId) {
    if (state.me && userId === state.me.id) return state.me.displayName || state.me.username;
    const m = state.members.find((x) => x.userId === userId);
    if (m) return m.displayName || m.username;
    return null;
  }
  // Inline mention chip HTML for a userId token. `name` is escaped; id is safe.
  function mentionChip(id) {
    const name = memberName(id) || "user";
    const me = state.me && id === state.me.id;
    return '<span class="mention' + (me ? " mention-me" : "") + '">@' + escape(name) + '</span>';
  }

  /**
   * Hand-written Markdown -> safe HTML. Escapes FIRST, then formats. Supports
   * fenced ``` code blocks ```, inline `code`, > blockquotes, **bold**,
   * __underline__, *italic* / _italic_, ~~strike~~, ||spoiler||, and <@id>
   * mention tokens. Never injects raw user HTML.
   */
  function renderMarkdown(raw) {
    if (raw == null || raw === "") return "";
    const escaped = escape(String(raw));
    const lines = escaped.split("\n");
    const pieces = []; // { block, html }
    let i = 0;
    while (i < lines.length) {
      const line = lines[i];
      if (/^```/.test(line)) {
        // single-line ```code```
        const single = line.match(/^```(.+?)```\s*$/);
        if (single) {
          pieces.push({ block: true, html: '<pre class="md-code"><code>' + single[1] + "</code></pre>" });
          i++; continue;
        }
        // multi-line fenced block (optional language tag on the opening fence)
        const code = [];
        i++;
        while (i < lines.length && !/^```\s*$/.test(lines[i])) { code.push(lines[i]); i++; }
        if (i < lines.length) i++; // consume closing fence
        pieces.push({ block: true, html: '<pre class="md-code"><code>' + code.join("\n") + "</code></pre>" });
        continue;
      }
      if (/^&gt;\s?/.test(line)) {
        const quote = [];
        while (i < lines.length && /^&gt;\s?/.test(lines[i])) {
          quote.push(applyInline(lines[i].replace(/^&gt;\s?/, "")));
          i++;
        }
        pieces.push({ block: true, html: '<blockquote class="md-quote">' + quote.join("<br>") + "</blockquote>" });
        continue;
      }
      pieces.push({ block: false, html: applyInline(line) });
      i++;
    }
    let out = "";
    pieces.forEach((p, idx) => {
      if (idx > 0 && !pieces[idx - 1].block && !p.block) out += "<br>";
      out += p.html;
    });
    return out;
  }

  // Inline formatting on a single, already-escaped line.
  function applyInline(s) {
    // 1) inline code — protect its contents from further formatting.
    // Triple-backtick spans first (mid-line ```x```), then single `x`.
    const codes = [];
    s = s.replace(/```([^`\n]+?)```/g, (m, c) => { codes.push(c); return "[[CODE" + (codes.length - 1) + "]]"; });
    s = s.replace(/`([^`\n]+)`/g, (m, c) => { codes.push(c); return "[[CODE" + (codes.length - 1) + "]]"; });
    // 2) spoiler / bold / underline / strike (doubles before singles)
    s = s.replace(/\|\|([\s\S]+?)\|\|/g, '<span class="spoiler">$1</span>');
    s = s.replace(/\*\*([\s\S]+?)\*\*/g, "<strong>$1</strong>");
    s = s.replace(/__([\s\S]+?)__/g, "<u>$1</u>");
    s = s.replace(/~~([\s\S]+?)~~/g, "<del>$1</del>");
    // 3) italic (single * or _), guarding against the doubles handled above
    s = s.replace(/(^|[^*])\*([^*\s][^*]*?)\*(?!\*)/g, "$1<em>$2</em>");
    s = s.replace(/(^|[^_\w])_([^_\s][^_]*?)_(?![_\w])/g, "$1<em>$2</em>");
    // 4) mention tokens <@id> (escaped as &lt;@id&gt;)
    s = s.replace(/&lt;@([\w-]+)&gt;/g, (m, id) => mentionChip(id));
    // 5) auto-link URLs, then restore protected inline code
    s = link(s);
    s = s.replace(/\[\[CODE(\d+)\]\]/g, (m, n) => '<code class="md-inline-code">' + codes[+n] + "</code>");
    return s;
  }

  // Convert @DisplayName drafts back into <@userId> tokens before sending.
  function convertMentions(text) {
    const entries = Object.entries(state.draftMentions);
    entries.sort((a, b) => b[0].length - a[0].length);
    for (const [name, uid] of entries) {
      const pat = new RegExp("@" + escapeRegex(name) + "(?![\\w])", "g");
      text = text.replace(pat, "<@" + uid + ">");
    }
    return text;
  }

  // Copy text to clipboard, with a graceful fallback.
  function copyText(t) {
    const s = String(t == null ? "" : t);
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(s).then(() => toast("Copied"), () => fallbackCopy(s));
    } else { fallbackCopy(s); }
  }
  function fallbackCopy(s) {
    try {
      const ta = h("textarea", { style: "position:fixed;opacity:0" });
      ta.value = s;
      document.body.appendChild(ta);
      ta.focus(); ta.select();
      document.execCommand("copy");
      ta.remove();
      toast("Copied");
    } catch { toast("Copy failed", true); }
  }
  function copyMessageLink(m) {
    const cid = m.channelId || (state.currentChannel && state.currentChannel.id);
    copyText(location.origin + "/#m/" + cid + "/" + m.id);
  }

  // Deterministic avatar color from an id/name.
  const AVATAR_COLORS = ["#5865f2","#3ba55d","#faa61a","#ed4245","#eb459e","#9b59b6","#1abc9c","#e67e22","#607d8b"];
  function colorFor(str) {
    let hash = 0;
    for (let i = 0; i < String(str).length; i++) hash = (hash * 31 + str.charCodeAt(i)) | 0;
    return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length];
  }

  // Initials for avatars / server icons (up to 2 chars).
  function initials(name) {
    const parts = String(name || "?").trim().split(/\s+/).filter(Boolean);
    if (parts.length === 0) return "?";
    if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
    return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
  }

  /* ---- inline Lucide icons (MIT) ---- */
  const ICONS = {
    hash: '<line x1="4" x2="20" y1="9" y2="9"/><line x1="4" x2="20" y1="15" y2="15"/><line x1="10" x2="8" y1="3" y2="21"/><line x1="16" x2="14" y1="3" y2="21"/>',
    volume: '<path d="M11 4.7a.7.7 0 0 0-1.2-.5L6.4 7.6A1.4 1.4 0 0 1 5.4 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.4a1.4 1.4 0 0 1 1 .4l3.4 3.4a.7.7 0 0 0 1.2-.5z"/><path d="M16 9a5 5 0 0 1 0 6"/><path d="M19.4 18.4a9 9 0 0 0 0-12.8"/>',
    paperclip: '<path d="m21.44 11.05-9.19 9.19a6 6 0 0 1-8.49-8.49l8.57-8.57A4 4 0 1 1 18 8.84l-8.59 8.57a2 2 0 0 1-2.83-2.83l8.49-8.48"/>',
    mic: '<path d="M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" x2="12" y1="19" y2="22"/>',
    download: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" x2="12" y1="15" y2="3"/>',
    "external-link": '<path d="M15 3h6v6"/><path d="M10 14 21 3"/><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>',
    "message-circle": '<path d="M7.9 20A9 9 0 1 0 4 16.1L2 22Z"/>',
    phone: '<path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"/>',
    "mic-off": '<line x1="2" x2="22" y1="2" y2="22"/><path d="M18.89 13.23A7.12 7.12 0 0 0 19 12v-2"/><path d="M5 10v2a7 7 0 0 0 12 5"/><path d="M15 9.34V5a3 3 0 0 0-5.68-1.33"/><path d="M9 9v3a3 3 0 0 0 5.12 2.12"/><line x1="12" x2="12" y1="19" y2="22"/>',
    video: '<path d="m16 13 5.22 3.48a.5.5 0 0 0 .78-.42V7.87a.5.5 0 0 0-.75-.43L16 10.5"/><rect x="2" y="6" width="14" height="12" rx="2"/>',
    "screen-share": '<path d="M13 3H4a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-3"/><path d="M8 21h8"/><path d="M12 17v4"/><path d="m17 8 5-5"/><path d="M17 3h5v5"/>',
    "phone-off": '<path d="M10.68 13.31a16 16 0 0 0 3.41 2.6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7 2 2 0 0 1 1.72 2v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.42 19.42 0 0 1-3.33-2.67m-2.67-3.34a19.79 19.79 0 0 1-3.07-8.63A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91"/><line x1="22" x2="2" y1="2" y2="22"/>',
    bookmark: '<path d="m19 21-7-4-7 4V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/>',
    trash: '<path d="M3 6h18"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><line x1="10" x2="10" y1="11" y2="17"/><line x1="14" x2="14" y1="11" y2="17"/>',
    reply: '<polyline points="9 14 4 9 9 4"/><path d="M20 20v-7a4 4 0 0 0-4-4H4"/>',
    pencil: '<path d="M12 20h9"/><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z"/>',
    send: '<path d="M14.54 21.69a.5.5 0 0 0 .94-.03l6.5-19a.5.5 0 0 0-.64-.63l-19 6.5a.5.5 0 0 0-.02.94l7.93 3.18a2 2 0 0 1 1.11 1.11z"/><path d="m21.85 2.15-10.94 10.94"/>',
    plus: '<path d="M5 12h14"/><path d="M12 5v14"/>',
    compass: '<circle cx="12" cy="12" r="10"/><polygon points="16.24 7.76 14.12 14.12 7.76 16.24 9.88 9.88 16.24 7.76"/>',
    users: '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
    user: '<circle cx="12" cy="8" r="5"/><path d="M20 21a8 8 0 0 0-16 0"/>',
    "log-out": '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/>',
    x: '<path d="M18 6 6 18"/><path d="m6 6 12 12"/>',
    "chevron-left": '<path d="m15 18-6-6 6-6"/>',
    "chevron-up": '<path d="m18 15-6-6-6 6"/>',
    "chevron-down": '<path d="m6 9 6 6 6-6"/>',
    hourglass: '<path d="M5 22h14"/><path d="M5 2h14"/><path d="M17 22v-4.17a2 2 0 0 0-.59-1.41L12 12l-4.41 4.41A2 2 0 0 0 7 17.83V22"/><path d="M7 2v4.17a2 2 0 0 0 .59 1.41L12 12l4.41-4.41A2 2 0 0 0 17 6.17V2"/>',
    file: '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/>',
    smile: '<circle cx="12" cy="12" r="10"/><path d="M8 14s1.5 2 4 2 4-2 4-2"/><line x1="9" x2="9.01" y1="9" y2="9"/><line x1="15" x2="15.01" y1="9" y2="9"/>',
    pin: '<path d="M12 17v5"/><path d="M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z"/>',
    "more-horizontal": '<circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/><circle cx="5" cy="12" r="1"/>',
    "at-sign": '<circle cx="12" cy="12" r="4"/><path d="M16 8v5a3 3 0 0 0 6 0v-1a10 10 0 1 0-4 8"/>',
    "arrow-down": '<path d="M12 5v14"/><path d="m19 12-7 7-7-7"/>',
    copy: '<rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>',
    check: '<path d="M20 6 9 17l-5-5"/>',
    play: '<polygon points="6 3 20 12 6 21 6 3"/>',
    pause: '<rect x="6" y="4" width="4" height="16" rx="1"/><rect x="14" y="4" width="4" height="16" rx="1"/>',
    settings: '<path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="3"/>',
    bell: '<path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0"/>',
    "bell-off": '<path d="M8.7 3A6 6 0 0 1 18 8a21.3 21.3 0 0 0 .6 5"/><path d="M17 17H3s3-2 3-9a4.67 4.67 0 0 1 .3-1.7"/><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0"/><path d="m2 2 20 20"/>',
    "user-plus": '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><line x1="19" x2="19" y1="8" y2="14"/><line x1="22" x2="16" y1="11" y2="11"/>',
    shield: '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/>',
    "mail-open": '<path d="M21.2 8.4c.5.38.8.97.8 1.6v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V10a2 2 0 0 1 .8-1.6l8-6a2 2 0 0 1 2.4 0z"/><path d="m22 10-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 10"/>',
    lock: '<rect width="18" height="11" x="3" y="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>',
    "lock-open": '<rect width="18" height="11" x="3" y="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 9.9-1"/>',
    headphones: '<path d="M3 14h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-5a9 9 0 0 1 18 0v5a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3"/>',
    "headphone-off": '<path d="M3 14h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-5a9 9 0 0 1 13.8-7.6"/><path d="M20.6 8.4A9 9 0 0 1 21 11v5a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3"/><line x1="2" x2="22" y1="2" y2="22"/>',
    search: '<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>',
    "alert-triangle": '<path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"/><path d="M12 9v4"/><path d="M12 17h.01"/>',
  };

  /**
   * Build an inline Lucide SVG icon element.
   * @param name  key in ICONS
   * @param size  px (default 18)
   * @param filled  fill with currentColor (used for the "saved" bookmark)
   */
  function icon(name, size = 18, filled = false) {
    const NS = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(NS, "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("width", size);
    svg.setAttribute("height", size);
    svg.setAttribute("fill", filled ? "currentColor" : "none");
    svg.setAttribute("stroke", "currentColor");
    svg.setAttribute("stroke-width", "2");
    svg.setAttribute("stroke-linecap", "round");
    svg.setAttribute("stroke-linejoin", "round");
    svg.setAttribute("class", "icon icon-" + name);
    svg.innerHTML = ICONS[name] || "";
    return svg;
  }

  /* ---- presence helpers ---- */
  function presenceState(p) {
    if (!p || p.online === false) return "offline";
    if (!p.online && p.status == null) return "offline";
    return p.status || "online";
  }
  function statusLabel(st) {
    return ({ online: "Online", idle: "Idle", dnd: "Do Not Disturb", offline: "Offline" })[st] || "Offline";
  }
  function statusDot(userId) {
    const st = presenceState(state.presence[userId]);
    return h("span", { class: "status-dot " + st, dataset: { presence: userId }, "aria-label": statusLabel(st) });
  }
  // Update every rendered status dot in place + refresh the user bar text.
  function applyPresence() {
    renderUserBar();
    document.querySelectorAll("[data-presence]").forEach((el) => {
      const st = presenceState(state.presence[el.dataset.presence]);
      el.className = "status-dot " + st;
      el.setAttribute("aria-label", statusLabel(st));
    });
  }
  function ensureMyPresence() {
    if (state.me && !state.presence[state.me.id]) {
      state.presence[state.me.id] = {
        online: true,
        status: state.me.status || "online",
        customStatus: state.me.customStatus || null,
      };
    }
  }

  /**
   * Avatar circle. When `userId` is supplied it gains a live status dot and
   * becomes a click target that opens that user's profile card.
   */
  function avatar(name, seed, cls = "", userId = null) {
    const el = h("div", {
      class: "avatar " + cls,
      style: `background:${colorFor(seed || name)}`,
      text: initials(name),
    });
    if (userId != null) {
      el.classList.add("has-status");
      el.appendChild(statusDot(userId));
      el.style.cursor = "pointer";
      el.setAttribute("role", "button");
      el.addEventListener("click", (e) => { e.stopPropagation(); openProfileCard(userId, el); });
      el.addEventListener("contextmenu", (e) => {
        e.preventDefault(); e.stopPropagation(); openUserMenu(userId, e.clientX, e.clientY, el);
      });
    }
    return el;
  }

  // Relative time from an ISO-8601 string, e.g. "5m", "2h", "Yesterday".
  function relativeTime(iso) {
    const d = new Date(iso), now = Date.now();
    const s = Math.floor((now - d.getTime()) / 1000);
    if (s < 10) return "just now";
    if (s < 60) return s + "s ago";
    const m = Math.floor(s / 60);
    if (m < 60) return m + "m ago";
    const hrs = Math.floor(m / 60);
    if (hrs < 24) return hrs + "h ago";
    const days = Math.floor(hrs / 24);
    if (days === 1) return "Yesterday";
    if (days < 7) return days + "d ago";
    return d.toLocaleDateString();
  }
  function fullTime(iso) { return new Date(iso).toLocaleString(); }
  function clockTime(iso) {
    return new Date(iso).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  }

  function formatBytes(n) {
    if (!n && n !== 0) return "";
    if (n < 1024) return n + " B";
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KB";
    if (n < 1024 * 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + " MB";
    return (n / 1024 / 1024 / 1024).toFixed(1) + " GB";
  }

  function isImage(a) { return a.contentType && a.contentType.startsWith("image/"); }

  // Transient toast notifications.
  function toast(msg, isError = false) {
    const host = $("toast-host");
    const el = h("div", { class: "toast" + (isError ? " error" : ""), text: msg });
    host.appendChild(el);
    setTimeout(() => {
      el.style.transition = "opacity .3s";
      el.style.opacity = "0";
      setTimeout(() => el.remove(), 300);
    }, isError ? 4500 : 2500);
  }

  // Am I an admin of the current guild? (owner or role==admin)
  function amAdmin() {
    return (
      state.myRole === "admin" ||
      (state.currentGuild && state.me && state.currentGuild.ownerId === state.me.id)
    );
  }

  /* =======================================================================
   * 2. API LAYER — thin wrapper over the REST contract
   * ===================================================================== */

  class ApiError extends Error {
    constructor(message, status) { super(message); this.status = status; }
  }

  /**
   * Perform an authenticated JSON request.
   * @param path   e.g. "/api/guilds"
   * @param opts   {method, body, form} — `form` sends FormData as-is.
   * Returns parsed JSON, or null for 204. Throws ApiError on non-2xx.
   */
  async function api(path, opts = {}) {
    const headers = {};
    if (state.token) headers["Authorization"] = "Bearer " + state.token;

    let payload;
    if (opts.form) {
      payload = opts.form; // browser sets multipart boundary
    } else if (opts.body !== undefined) {
      headers["Content-Type"] = "application/json";
      payload = JSON.stringify(opts.body);
    }

    let res;
    try {
      res = await fetch(path, { method: opts.method || "GET", headers, body: payload });
    } catch (e) {
      throw new ApiError("Network error — is the server running?", 0);
    }

    if (res.status === 401 && state.token && state.me) {
      // Token expired mid-session.
      handleLogout(true);
      throw new ApiError("Session expired, please log in again", 401);
    }
    if (res.status === 204) return null;

    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { data = text; }

    if (!res.ok) {
      const msg = (data && data.error) || res.statusText || "Request failed";
      throw new ApiError(msg, res.status);
    }
    return data;
  }

  // Concrete endpoints (keeps call sites readable).
  const API = {
    register: (b) => api("/api/auth/register", { method: "POST", body: b }),
    login: (b) => api("/api/auth/login", { method: "POST", body: b }),
    me: () => api("/api/auth/me"),

    myGuilds: () => api("/api/guilds"),
    allGuilds: () => api("/api/guilds/all"),
    createGuild: (name) => api("/api/guilds", { method: "POST", body: { name } }),
    joinGuild: (id) => api(`/api/guilds/${id}/join`, { method: "POST" }),
    renameGuild: (id, name) => api(`/api/guilds/${id}`, { method: "PATCH", body: { name } }),
    leaveGuild: (id) => api(`/api/guilds/${id}/leave`, { method: "POST" }),
    deleteGuild: (id) => api(`/api/guilds/${id}`, { method: "DELETE" }),
    createChannel: (gid, name, type, adminOnly) =>
      api(`/api/guilds/${gid}/channels`, { method: "POST", body: { name, type, adminOnly: !!adminOnly } }),
    renameChannel: (id, name) => api(`/api/channels/${id}`, { method: "PATCH", body: { name } }),
    updateChannel: (id, body) => api(`/api/channels/${id}`, { method: "PATCH", body }),
    setChannelAdminOnly: (id, adminOnly) =>
      api(`/api/channels/${id}/admin-only`, { method: "PUT", body: { adminOnly } }),
    deleteChannel: (id) => api(`/api/channels/${id}`, { method: "DELETE" }),

    members: (gid) => api(`/api/guilds/${gid}/members`),
    addMember: (gid, username) =>
      api(`/api/guilds/${gid}/members`, { method: "POST", body: { username } }),
    setRole: (gid, uid, role) =>
      api(`/api/guilds/${gid}/members/${uid}/role`, { method: "PUT", body: { role } }),
    kick: (gid, uid) => api(`/api/guilds/${gid}/members/${uid}`, { method: "DELETE" }),

    messages: (cid, before) =>
      api(`/api/channels/${cid}/messages?limit=50` + (before ? `&before=${before}` : "")),
    send: (cid, content, attachmentIds, replyToId) =>
      api(`/api/channels/${cid}/messages`, { method: "POST", body: { content, attachmentIds, replyToId } }),
    editMsg: (id, content) => api(`/api/messages/${id}`, { method: "PATCH", body: { content } }),
    saveMsg: (id) => api(`/api/messages/${id}/save`, { method: "POST" }),
    unsaveMsg: (id) => api(`/api/messages/${id}/save`, { method: "DELETE" }),
    deleteMsg: (id) => api(`/api/messages/${id}`, { method: "DELETE" }),

    getUser: (id) => api(`/api/users/${id}`),
    updateMe: (body) => api(`/api/users/me`, { method: "PATCH", body }),

    getSettings: () => api(`/api/users/me/settings`),
    putSettings: (body) => api(`/api/users/me/settings`, { method: "PUT", body }),
    deleteAccount: () => api(`/api/users/me`, { method: "DELETE" }),
    search: (params) => api(`/api/search?` + new URLSearchParams(params).toString()),

    unfurl: (u) => api(`/api/unfurl?url=${encodeURIComponent(u)}`),
    gifFeatured: (pos) => api(`/api/gifs/featured` + (pos ? `?pos=${encodeURIComponent(pos)}` : "")),
    gifSearch: (q, pos) =>
      api(`/api/gifs/search?q=${encodeURIComponent(q)}` + (pos ? `&pos=${encodeURIComponent(pos)}` : "")),

    upload: (file) => {
      const fd = new FormData();
      fd.append("file", file);
      return api("/api/uploads", { method: "POST", form: fd });
    },
    uploadVoice: (file, durationMs, waveform) => {
      const fd = new FormData();
      fd.append("file", file);
      fd.append("durationMs", String(durationMs));
      fd.append("waveform", waveform);
      return api("/api/uploads", { method: "POST", form: fd });
    },
    voiceToken: (cid) => api(`/api/channels/${cid}/voice-token`, { method: "POST" }),

    listDms: () => api("/api/dms"),
    openDm: (userId) => api("/api/dms", { method: "POST", body: { userId } }),
    openDmByUsername: (username) => api("/api/dms", { method: "POST", body: { username } }),

    react: (id, emoji) => api(`/api/messages/${id}/react`, { method: "POST", body: { emoji } }),
    pin: (id) => api(`/api/messages/${id}/pin`, { method: "POST" }),
    unpin: (id) => api(`/api/messages/${id}/pin`, { method: "DELETE" }),
    pins: (cid) => api(`/api/channels/${cid}/pins`),
    readState: (gid) => api(`/api/guilds/${gid}/read-state`),
    ack: (cid, lastReadId) => api(`/api/channels/${cid}/ack`, { method: "POST", body: { lastReadId } }),
  };

  /* =======================================================================
   * 3. WEBSOCKET LAYER — realtime, with subscribe tracking + backoff reconnect
   * ===================================================================== */

  const ws = {
    sock: null,
    ready: false,
    backoff: 1000,
    reconnectTimer: null,
    subscribed: null,      // channelId we're currently subscribed to
    guildSubs: new Set(),  // every guildId we're subscribed to (cross-server unread/mentions)
    dmSubs: new Set(),     // every DM channel we're subscribed to (DMs have no guild)

    connect() {
      if (!state.token) return;
      const proto = location.protocol === "https:" ? "wss" : "ws";
      const url = `${proto}://${location.host}/ws?token=${encodeURIComponent(state.token)}`;
      let sock;
      try { sock = new WebSocket(url); } catch { this.scheduleReconnect(); return; }
      this.sock = sock;

      sock.onopen = () => { /* wait for {type:'ready'} */ };
      sock.onmessage = (ev) => {
        let msg;
        try { msg = JSON.parse(ev.data); } catch { return; }
        if (msg.type === "ready") {
          this.ready = true;
          this.backoff = 1000;
          // (Re)subscribe to EVERY guild we're in, so unread/mention indicators on
          // the server rail update live for servers we aren't currently viewing.
          const guilds = new Set(this.guildSubs);
          this.guildSubs = new Set();
          for (const gid of guilds) this.subscribeGuild(gid);
          // re-subscribe to our DM channels too
          const dmIds = new Set(this.dmSubs);
          this.dmSubs = new Set();
          this.subscribeDms([...dmIds]);
          // …and to whatever text channel is open (for typing indicators).
          if (state.currentChannel && state.currentChannel.type === "text") {
            this.subscribed = null;
            this.subscribe(state.currentChannel.id);
          }
          // re-announce voice state if we're in a call
          if (voice.room) voice.broadcastState();
          return;
        }
        this.dispatch(msg);
      };
      sock.onclose = () => {
        this.ready = false;
        this.sock = null;
        if (state.token) this.scheduleReconnect();
      };
      sock.onerror = () => { try { sock.close(); } catch {} };
    },

    scheduleReconnect() {
      if (this.reconnectTimer) return;
      const delay = this.backoff;
      this.reconnectTimer = setTimeout(() => {
        this.reconnectTimer = null;
        this.backoff = Math.min(this.backoff * 2, 15000); // cap at 15s
        this.connect();
      }, delay);
    },

    send(obj) {
      if (this.sock && this.ready && this.sock.readyState === WebSocket.OPEN) {
        this.sock.send(JSON.stringify(obj));
        return true;
      }
      return false;
    },

    subscribe(channelId) {
      if (this.subscribed === channelId) return;
      if (this.subscribed) this.send({ type: "unsubscribe", channelId: this.subscribed });
      this.subscribed = channelId;
      this.send({ type: "subscribe", channelId });
    },

    subscribeGuild(guildId) {
      if (this.guildSubs.has(guildId)) return;
      this.guildSubs.add(guildId);
      this.send({ type: "subscribe_guild", guildId });
    },
    subscribeGuilds(ids) { for (const id of ids) this.subscribeGuild(id); },
    // DM channels are subscribed persistently (like guilds) so their messages +
    // unread badges arrive even when you're not looking at the conversation.
    subscribeDms(ids) {
      for (const id of ids) {
        if (this.dmSubs.has(id)) continue;
        this.dmSubs.add(id);
        this.send({ type: "subscribe", channelId: id });
      }
    },
    unsubscribeGuild(guildId) {
      if (!this.guildSubs.has(guildId)) return;
      this.guildSubs.delete(guildId);
      this.send({ type: "unsubscribe_guild", guildId });
    },

    unsubscribe() {
      if (this.subscribed) {
        this.send({ type: "unsubscribe", channelId: this.subscribed });
        this.subscribed = null;
      }
    },

    typing(channelId) { this.send({ type: "typing", channelId }); },

    close() {
      if (this.reconnectTimer) { clearTimeout(this.reconnectTimer); this.reconnectTimer = null; }
      this.ready = false;
      this.subscribed = null;
      if (this.sock) { try { this.sock.close(); } catch {} this.sock = null; }
    },

    // Route server -> client events.
    dispatch(msg) {
      const d = msg.data;
      switch (msg.type) {
        case "message":
          if (state.currentChannel && d.channelId === state.currentChannel.id) {
            onIncomingMessage(d);
          } else {
            markChannelUnreadFromMessage(d);
            markDmUnread(d.channelId);
          }
          break;
        case "message_updated":
          if (state.currentChannel && d.channelId === state.currentChannel.id) {
            upsertMessage(d);
          }
          break;
        case "message_deleted":
          if (state.currentChannel && d.channelId === state.currentChannel.id) {
            removeMessage(d.messageId);
          }
          break;
        case "typing":
          if (
            state.currentChannel &&
            d.channelId === state.currentChannel.id &&
            state.me && d.userId !== state.me.id
          ) {
            showTyping(d.userId, d.name);
          }
          break;
        case "voice_presence_snapshot":
          // Full map of channelId -> participants on (re)connect.
          state.voicePresence = d || {};
          renderChannels();
          break;
        case "voice_presence": {
          // Full current participant list for one voice channel (with mute/deafen/screen).
          const list = d.participants || [];
          if (list.length) state.voicePresence[d.channelId] = list;
          else delete state.voicePresence[d.channelId];
          renderChannels();
          if (voice.room && voice.channelId === d.channelId) voice.rebuildRoster();
          break;
        }
        case "presence_snapshot": {
          // Full map of online users on (re)connect: { "<userId>": {status, customStatus} }
          state.presence = {};
          for (const [uid, info] of Object.entries(d || {})) {
            state.presence[uid] = {
              online: true,
              status: (info && info.status) || "online",
              customStatus: (info && info.customStatus) || null,
            };
          }
          ensureMyPresence();
          applyPresence();
          break;
        }
        case "presence_update": {
          // { userId, online, status, customStatus }
          state.presence[d.userId] = {
            online: !!d.online,
            status: d.status || (d.online ? "online" : null),
            customStatus: d.customStatus != null ? d.customStatus : null,
          };
          applyPresence();
          break;
        }
      }
    },
  };

  /* =======================================================================
   * 4. LIVEKIT LAYER — voice / video / screen share
   * ===================================================================== */

  const LK = window.LivekitClient; // undefined if the vendored script failed

  /* ---- media (voice/video) settings, persisted locally ---- */
  const MEDIA_KEY = "ephemeral_media_settings";
  const defaultMedia = {
    audioInput: "default", audioOutput: "default", videoInput: "default",
    echoCancellation: true, noiseSuppression: true, autoGainControl: true,
    voiceIsolation: true,      // Chromium's strongest built-in noise removal (overrides NS when on)
    inputVolume: 1,            // 0..2 gain applied to the mic via Web Audio
    hqAudio: false,            // opus music-quality preset for crisper voice
    cameraRes: "720",          // 480 | 720 | 1080
    screenRes: "1080",         // 720 | 1080 | 1440
    screenFps: 30,             // 15 | 30 | 60
    screenPriority: "balanced", // clarity | balanced | motion  -> contentHint
    inputMode: "voice",        // "voice" (activity) | "ptt" (push-to-talk)
    pttKey: "Space",           // KeyboardEvent.code for the PTT key
    pttRelease: 200,           // ms release delay
    afkTimeoutMin: 15,         // auto-disconnect from voice after N idle minutes (0 = never)
    notifSound: true,
  };
  let media = (() => {
    try { return { ...defaultMedia, ...(JSON.parse(localStorage.getItem(MEDIA_KEY)) || {}) }; }
    catch { return { ...defaultMedia }; }
  })();
  function saveMedia() { try { localStorage.setItem(MEDIA_KEY, JSON.stringify(media)); } catch {} pushSettings(); }

  // ---- voice inactivity auto-disconnect (Discord-style AFK timeout) ----
  // Discord auto-disconnects idle users from voice to save bandwidth. We mirror
  // that client-side: any interaction (or you speaking) resets the timer; after
  // the configured idle window with none, we leave the call.
  let _afkLast = Date.now();
  function afkBump() { _afkLast = Date.now(); }
  function afkCheck() {
    const mins = media.afkTimeoutMin || 0;
    if (!mins || !voice.room) return;
    if (Date.now() - _afkLast >= mins * 60000) {
      afkBump();               // reset so we only fire once
      voice.leave();
      toast("Disconnected from voice — inactive for " + mins + " min");
    }
  }

  // ---- settings persistence across restarts + devices (server-side) ----
  let _pushT = null;
  function pushSettings() {
    if (!state.token) return;
    clearTimeout(_pushT);
    _pushT = setTimeout(() => { API.putSettings({ media, muted }).catch(() => {}); }, 800);
  }
  async function syncSettingsFromServer() {
    try {
      const s = await API.getSettings();
      if (s && typeof s === "object") {
        if (s.media && typeof s.media === "object") { media = { ...defaultMedia, ...media, ...s.media }; try { localStorage.setItem(MEDIA_KEY, JSON.stringify(media)); } catch {} }
        if (s.muted && typeof s.muted === "object") { muted = s.muted; try { localStorage.setItem(MUTE_KEY, JSON.stringify(muted)); } catch {} }
      }
    } catch { /* first login / no settings yet */ }
  }

  // Enumerate input/output devices (labels require a prior getUserMedia grant).
  async function listDevices() {
    if (!navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) return { audioinput: [], audiooutput: [], videoinput: [] };
    let devs = [];
    try { devs = await navigator.mediaDevices.enumerateDevices(); } catch { return { audioinput: [], audiooutput: [], videoinput: [] }; }
    const by = { audioinput: [], audiooutput: [], videoinput: [] };
    for (const d of devs) if (by[d.kind]) by[d.kind].push({ deviceId: d.deviceId, label: d.label || d.kind });
    return by;
  }

  // ---- translate settings into LiveKit options ----
  function cameraCaptureOpts() {
    const dims = { "480": [854, 480], "720": [1280, 720], "1080": [1920, 1080] }[media.cameraRes] || [1280, 720];
    const o = { resolution: { width: dims[0], height: dims[1], frameRate: 30 } };
    if (media.videoInput && media.videoInput !== "default") o.deviceId = { exact: media.videoInput };
    return o;
  }
  // NB: livekit v2.20 defaults voiceIsolation:true, which silently OVERRIDES
  // noiseSuppression on Chromium — so we always pass it explicitly. And
  // restartTrack() REPLACES constraints (no merge), so this must be complete.
  function audioCaptureDefaults() {
    const o = {
      echoCancellation: media.echoCancellation,
      noiseSuppression: media.noiseSuppression,
      autoGainControl: media.autoGainControl,
      voiceIsolation: media.voiceIsolation,
      channelCount: 1, // mono keeps Opus DTX + RED enabled (loss resilience)
    };
    if (media.audioInput && media.audioInput !== "default") o.deviceId = { exact: media.audioInput };
    return o;
  }
  // Bitrates aligned with livekit's ScreenSharePresets (h720fps30=2M, h1080fps30=5M).
  function screenEncoding() {
    const base = { "720": 2_000_000, "1080": 5_000_000, "1440": 8_000_000 }[media.screenRes] || 5_000_000;
    const fps = media.screenFps || 30;
    const maxBitrate = Math.round(base * (fps >= 60 ? 1.6 : fps >= 30 ? 1 : 0.6));
    return { maxBitrate, maxFramerate: fps, priority: "high" };
  }
  function screenCaptureOpts() {
    const dims = { "720": [1280, 720], "1080": [1920, 1080], "1440": [2560, 1440] }[media.screenRes] || [1920, 1080];
    // contentHint: 'detail'/'text' keep text crisp; 'motion' favours smooth video.
    const hint = media.screenPriority === "motion" ? "motion" : media.screenPriority === "clarity" ? "text" : "detail";
    return {
      resolution: { width: dims[0], height: dims[1], frameRate: media.screenFps || 30 },
      contentHint: hint,
      audio: true,                 // capture tab/system audio when the browser offers it
      systemAudio: "include",
      selfBrowserSurface: "include",
      surfaceSwitching: "include",
    };
  }
  // Publish options for a screen share: single max-quality layer (no simulcast),
  // so every bit goes into crispness at the encoding we chose.
  function screenPublishOpts() {
    return { screenShareEncoding: screenEncoding(), simulcast: false };
  }
  function roomOptions() {
    return {
      adaptiveStream: true,
      dynacast: true,
      audioCaptureDefaults: audioCaptureDefaults(),
      videoCaptureDefaults: cameraCaptureOpts(),
      publishDefaults: {
        audioPreset: media.hqAudio ? LK.AudioPresets.musicHighQuality : LK.AudioPresets.music,
        dtx: true,
        red: true,
        screenShareEncoding: screenEncoding(),
      },
    };
  }

  // Apply the chosen output device to every remote audio element (and future ones).
  async function applyOutputSink() {
    const id = media.audioOutput;
    const els = document.querySelectorAll("#voice-audio-sink audio");
    for (const el of els) {
      if (el.setSinkId && id && id !== "default") { try { await el.setSinkId(id); } catch {} }
      else if (el.setSinkId && id === "default") { try { await el.setSinkId(""); } catch {} }
    }
  }

  // Apply audio changes to a LIVE call (device + echo/noise/AGC constraints + output).
  async function applyLiveAudio() {
    const r = voice.room;
    if (!r) return;
    try {
      const pub = r.localParticipant.getTrackPublication(LK.Track.Source.Microphone);
      const at = pub && pub.audioTrack;
      if (at && at.restartTrack) await at.restartTrack(audioCaptureDefaults());
    } catch (e) { /* device busy / denied — non-fatal */ }
    try { await r.switchActiveDevice("audiooutput", media.audioOutput === "default" ? "default" : media.audioOutput); } catch {}
    await applyOutputSink();
    voice.applyInputGain && voice.applyInputGain();
  }

  // The Voice & Video settings modal, with a live input-level meter.
  async function openMediaSettings() {
    const devices = await listDevices();
    // request a mic grant so device labels populate + the level meter has a stream
    let meterStream = null, audioCtx = null, raf = 0, gainNode = null, ownStream = false;
    const meterBars = [];
    const meter = h("div", { class: "meter" });
    for (let i = 0; i < 20; i++) { const b = h("span", { class: "meter-bar" }); meterBars.push(b); meter.appendChild(b); }

    const sels = {}; // kind -> <select>, so we can repopulate labels after the mic grant
    const fillSelect = (sel, list, cur) => {
      sel.innerHTML = "";
      sel.appendChild(h("option", { value: "default", text: "Default" }));
      for (const d of list) if (d.deviceId && d.deviceId !== "default") {
        sel.appendChild(h("option", { value: d.deviceId, text: d.label || d.deviceId.slice(0, 12) }));
      }
      sel.value = cur || "default";
      if (sel.value !== (cur || "default")) sel.value = "default"; // saved device unplugged
    };
    const selectRow = (label, kind, key, sub) => {
      const sel = h("select", { class: "text-input" });
      sels[kind] = sel;
      fillSelect(sel, devices[kind], media[key]);
      sel.addEventListener("change", async () => { media[key] = sel.value; saveMedia();
        if (kind === "audiooutput") { await applyLiveAudio(); }
        else if (kind === "audioinput") { await restartMeter(); await applyLiveAudio(); }
        else if (kind === "videoinput" && voice.room && voice.cam) { try { await voice.room.switchActiveDevice("videoinput", sel.value === "default" ? "default" : sel.value); } catch {} }
      });
      return field(label, sel, sub);
    };
    const toggleRow = (label, key, sub, onChange) => {
      const cb = h("input", { type: "checkbox" });
      cb.checked = !!media[key];
      const sw = h("label", { class: "switch" }, cb, h("span", { class: "slider" }));
      cb.addEventListener("change", async () => { media[key] = cb.checked; saveMedia(); if (onChange) await onChange(); });
      return h("div", { class: "set-row set-toggle" }, h("div", { class: "set-label" }, h("div", { text: label }), sub ? h("div", { class: "set-sub", text: sub }) : null), sw);
    };
    const segRow = (label, key, opts, onChange) => {
      const seg = h("div", { class: "seg" });
      for (const [val, txt] of opts) {
        const b = h("button", { class: String(media[key]) === String(val) ? "active" : "", text: txt });
        b.onclick = async () => { media[key] = (typeof val === "number") ? val : val; saveMedia();
          [...seg.children].forEach((c) => c.classList.remove("active")); b.classList.add("active"); if (onChange) await onChange(); };
        seg.appendChild(b);
      }
      return field(label, seg);
    };
    const field = (label, control, sub) => h("div", { class: "set-row" },
      h("div", { class: "set-label" }, h("div", { text: label }), sub ? h("div", { class: "set-sub", text: sub }) : null), control);

    // input volume slider — drives the real published gain live when in a call
    const vol = h("input", { type: "range", min: "0", max: "200", step: "1", class: "range" });
    vol.value = String(Math.round((media.inputVolume ?? 1) * 100));
    const volVal = h("span", { class: "range-val", text: vol.value + "%" });
    vol.addEventListener("input", () => {
      volVal.textContent = vol.value + "%";
      media.inputVolume = (+vol.value) / 100;
      if (gainNode && ownStream) gainNode.gain.value = media.inputVolume; // local meter preview
      if (voice._gainProc) voice._gainProc._setGain(media.inputVolume);   // live published gain
    });
    vol.addEventListener("change", () => { saveMedia(); voice.applyInputGain(); });

    // push-to-talk keybind + release delay (revealed when Input Mode = PTT)
    const keyLabel = (code) => code === "Space" ? "Space" : code.replace(/^Key/, "").replace(/^Digit/, "").replace(/Left$/, " (L)").replace(/Right$/, " (R)");
    const pttKeyBtn = h("button", { class: "btn btn-secondary", text: keyLabel(media.pttKey) });
    pttKeyBtn.onclick = () => {
      pttKeyBtn.textContent = "Press a key…";
      const onKey = (e) => { e.preventDefault(); media.pttKey = e.code; saveMedia(); pttKeyBtn.textContent = keyLabel(e.code); document.removeEventListener("keydown", onKey, true); };
      document.addEventListener("keydown", onKey, true);
    };
    const rel = h("input", { type: "range", class: "range", min: "0", max: "2000", step: "50", value: String(media.pttRelease) });
    const relVal = h("span", { class: "range-val", text: (media.pttRelease || 0) + "ms" });
    rel.addEventListener("input", () => { relVal.textContent = rel.value + "ms"; media.pttRelease = +rel.value; });
    rel.addEventListener("change", saveMedia);
    const pttFields = h("div", { class: "ptt-fields" },
      field("Keybind", pttKeyBtn, "Push-to-talk only works while this browser tab is focused."),
      field("Release delay", h("div", { class: "range-wrap" }, rel, relVal)));
    pttFields.style.display = media.inputMode === "ptt" ? "" : "none";
    const modeSeg = segRow("Input Mode", "inputMode", [["voice", "Voice Activity"], ["ptt", "Push to Talk"]],
      () => { pttFields.style.display = media.inputMode === "ptt" ? "" : "none"; if (voice.applyInputMode) voice.applyInputMode(); });

    // inactivity auto-disconnect (Discord-style AFK timeout)
    const afkSel = h("select", { class: "text-input" });
    [["0", "Never"], ["5", "After 5 min"], ["15", "After 15 min"], ["30", "After 30 min"], ["60", "After 60 min"]]
      .forEach(([v, t]) => afkSel.appendChild(h("option", { value: v, text: t })));
    afkSel.value = String(media.afkTimeoutMin ?? 15);
    afkSel.addEventListener("change", () => { media.afkTimeoutMin = +afkSel.value; saveMedia(); afkBump(); });

    // one tab per section header
    const sections = [
      { name: "Voice", rows: [
        selectRow("Input Device", "audioinput", "audioInput"),
        selectRow("Output Device", "audiooutput", "audioOutput"),
        field("Input Volume", h("div", { class: "range-wrap" }, vol, volVal)),
        field("Mic Test", meter, "Speak — the bars show your input level."),
        modeSeg, pttFields,
        toggleRow("Echo Cancellation", "echoCancellation", "Removes speaker echo picked up by your mic. Turn off with headphones for a touch more fidelity.", applyLiveAudio),
        toggleRow("Noise Suppression", "noiseSuppression", "Filters background noise (fans, keyboards, hum).", applyLiveAudio),
        toggleRow("Voice Isolation", "voiceIsolation", "Strongest noise removal (Chromium). Overrides Noise Suppression while on.", applyLiveAudio),
        toggleRow("Automatic Gain Control", "autoGainControl", "Auto-levels your volume so you stay audible.", applyLiveAudio),
        toggleRow("High-Quality Audio", "hqAudio", "96 kbps Opus for crisper voice (more bandwidth). Applies next call.", null),
        field("Disconnect When Inactive", afkSel, "Automatically leaves the call after you've been idle this long (like Discord's AFK timeout)."),
      ] },
      { name: "Video", rows: [
        selectRow("Camera", "videoinput", "videoInput"),
        segRow("Camera Quality", "cameraRes", [["480", "480p"], ["720", "720p"], ["1080", "1080p"]]),
      ] },
      { name: "Screen Share", rows: [
        segRow("Resolution", "screenRes", [["720", "720p"], ["1080", "1080p"], ["1440", "1440p"]], reshareIfActive),
        segRow("Frame Rate", "screenFps", [[15, "15 fps"], [30, "30 fps"], [60, "60 fps"]], reshareIfActive),
        segRow("Priority", "screenPriority", [["clarity", "Clarity"], ["balanced", "Balanced"], ["motion", "Motion"]], reshareIfActive),
        h("div", { class: "set-note", text: "Screen-share changes take effect the next time you start sharing." }),
      ] },
    ];
    const tabbar = h("div", { class: "set-tabs" });
    const panels = sections.map((s, i) => h("div", { class: "set-tabpanel" + (i ? " hidden" : "") }, ...s.rows));
    sections.forEach((s, i) => {
      const btn = h("button", { class: "set-tab" + (i ? "" : " active"), text: s.name });
      btn.onclick = () => {
        [...tabbar.children].forEach((c) => c.classList.remove("active"));
        btn.classList.add("active");
        panels.forEach((p, j) => p.classList.toggle("hidden", j !== i));
      };
      tabbar.appendChild(btn);
    });
    const body = [tabbar, ...panels];

    const { backdrop } = modal({
      title: "Voice & Video Settings",
      body,
      footer: [h("button", { class: "btn", text: "Done", onclick: closeModal })],
    });

    async function startMeter(deviceId) {
      // In a call: meter the ACTUAL published mic track (post-processing) rather
      // than opening a second capture (which some devices refuse).
      ownStream = false;
      const livePub = voice.room && voice.room.localParticipant.getTrackPublication(LK.Track.Source.Microphone);
      const liveTrack = livePub && livePub.audioTrack && livePub.audioTrack.mediaStreamTrack;
      if (liveTrack && liveTrack.readyState === "live") {
        meterStream = new MediaStream([liveTrack]);
      } else {
        try {
          meterStream = await navigator.mediaDevices.getUserMedia({
            audio: { ...(deviceId && deviceId !== "default" ? { deviceId: { exact: deviceId } } : {}),
              echoCancellation: media.echoCancellation, noiseSuppression: media.noiseSuppression, autoGainControl: media.autoGainControl },
          });
          ownStream = true;
        } catch { return; }
      }
      audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      const src = audioCtx.createMediaStreamSource(meterStream);
      gainNode = audioCtx.createGain();
      // Live track already has the published gain applied; preview gain only on our own capture.
      gainNode.gain.value = ownStream ? (media.inputVolume ?? 1) : 1;
      const analyser = audioCtx.createAnalyser();
      analyser.fftSize = 512;
      src.connect(gainNode); gainNode.connect(analyser);
      const data = new Uint8Array(analyser.frequencyBinCount);
      const tick = () => {
        analyser.getByteTimeDomainData(data);
        let peak = 0; for (let i = 0; i < data.length; i++) { const v = Math.abs(data[i] - 128) / 128; if (v > peak) peak = v; }
        const lit = Math.min(meterBars.length, Math.round(peak * meterBars.length * 1.6));
        meterBars.forEach((b, i) => b.classList.toggle("on", i < lit));
        raf = requestAnimationFrame(tick);
      };
      tick();
    }
    async function stopMeter() {
      if (raf) cancelAnimationFrame(raf), raf = 0;
      if (audioCtx) { try { await audioCtx.close(); } catch {} audioCtx = null; }
      if (meterStream && ownStream) meterStream.getTracks().forEach((t) => t.stop());
      meterStream = null;
    }
    async function restartMeter() { await stopMeter(); await startMeter(media.audioInput); }
    // Start the meter (this requests the mic grant), then re-enumerate: labels
    // and stable deviceIds only exist AFTER a grant, so repopulate the selects.
    await startMeter(media.audioInput);
    const named = await listDevices();
    fillSelect(sels.audioinput, named.audioinput, media.audioInput);
    fillSelect(sels.audiooutput, named.audiooutput, media.audioOutput);
    fillSelect(sels.videoinput, named.videoinput, media.videoInput);
    // tear down the meter when the modal closes
    const mo = new MutationObserver(() => { if (!document.getElementById("modal-root").contains(backdrop)) { stopMeter(); mo.disconnect(); } });
    mo.observe($("modal-root"), { childList: true });

    async function reshareIfActive() {
      if (voice.room && voice.screen) {
        try {
          await voice.room.localParticipant.setScreenShareEnabled(false);
          await voice.room.localParticipant.setScreenShareEnabled(true, screenCaptureOpts(), screenPublishOpts());
        } catch {
          voice.screen = false; voice.syncControls();
          toast("Share your screen again to apply the new quality", true);
        }
      }
    }
  }

  const voice = {
    room: null,
    channelId: null,
    tiles: new Map(), // key -> tile element (key = identity, or identity + ":screen")
    focusedKey: null, // spotlight: the tile key filling the main pane, or null for grid
    localMutes: new Set(), // identities muted just for me (local volume off)
    mic: false,
    cam: false,
    screen: false,
    deafened: false,

    available() { return !!LK; },

    volumes: {}, // identity -> 0..2 gain, local to this viewer
    volumeOf(id) { return this.volumes[id] == null ? 1 : this.volumes[id]; },
    setVolume(id, v) {
      this.volumes[id] = v;
      // LiveKit handles >100% amplification via its web-audio mix when available…
      const p = this.room && this.room.remoteParticipants && this.room.remoteParticipants.get(id);
      if (p && p.setVolume) { try { p.setVolume(v); } catch {} }
      // …and attenuation on the raw <audio> elements works everywhere.
      document.querySelectorAll('#voice-audio-sink audio[data-identity="' + CSS.escape(id) + '"]')
        .forEach((el) => { el.volume = Math.min(1, v); });
    },
    isLocallyMuted(id) { return this.localMutes.has(id); },
    toggleLocalMute(id) {
      if (this.localMutes.has(id)) this.localMutes.delete(id); else this.localMutes.add(id);
      const on = this.localMutes.has(id);
      document.querySelectorAll('#voice-audio-sink audio[data-identity="' + CSS.escape(id) + '"]')
        .forEach((el) => { el.muted = on; });
      const tile = this.tiles.get(id);
      if (tile) tile.classList.toggle("local-muted", on);
      toast(on ? "Muted for you" : "Unmuted");
    },

    // ---- input gain: a Web Audio GainNode inserted via livekit's track processor
    // API, so the slider changes what OTHERS hear (not just the local meter).
    _gainProc: null,
    makeGainProcessor() {
      let src = null, gain = null, dest = null, own = null;
      return {
        name: "input-gain",
        processedTrack: undefined,
        _setGain(v) { if (gain) gain.gain.value = v; },
        async init(opts) {
          const ctx = opts.audioContext || (own = new (window.AudioContext || window.webkitAudioContext)());
          if (ctx.state === "suspended") { try { await ctx.resume(); } catch {} }
          src = ctx.createMediaStreamSource(new MediaStream([opts.track]));
          gain = ctx.createGain();
          gain.gain.value = media.inputVolume ?? 1;
          dest = ctx.createMediaStreamDestination();
          src.connect(gain); gain.connect(dest);
          this.processedTrack = dest.stream.getAudioTracks()[0];
        },
        async restart(opts) { try { await this.destroy(); } catch {} await this.init(opts); },
        async destroy() {
          try { src && src.disconnect(); } catch {}
          try { gain && gain.disconnect(); } catch {}
          if (own) { try { await own.close(); } catch {} own = null; }
          src = gain = dest = null; this.processedTrack = undefined;
        },
      };
    },
    async applyInputGain() {
      if (!this.room) return;
      const pub = this.room.localParticipant.getTrackPublication(LK.Track.Source.Microphone);
      const at = pub && pub.audioTrack;
      if (!at || !at.setProcessor) return;
      const v = media.inputVolume ?? 1;
      try {
        if (this._gainProc && this._gainProc.processedTrack) {
          this._gainProc._setGain(v); // live-adjust, no re-publish
        } else if (Math.abs(v - 1) > 0.01) {
          this._gainProc = this.makeGainProcessor();
          await at.setProcessor(this._gainProc);
          this._gainProc._setGain(v);
        }
      } catch (e) { this._gainProc = null; /* processor unsupported — slider becomes a no-op */ }
    },

    async join(channel) {
      if (!this.available()) { toast("Voice is unavailable (LiveKit failed to load)", true); return; }
      if (this.room && this.channelId === channel.id) { renderVoiceConnected(); this.rebuildRoster(); return; } // already here
      if (this.room) { await this.leave(); } // switching to a different voice channel
      if (!this._escBound) {
        this._escBound = true;
        document.addEventListener("keydown", (e) => { if (e.key === "Escape" && this.focusedKey) this.unfocus(); });
      }
      setVoiceStatus("Connecting…");
      let tok;
      try {
        tok = await API.voiceToken(channel.id);
      } catch (e) { toast(e.message, true); setVoiceStatus(""); return; }

      const room = new LK.Room(roomOptions());
      this.room = room;
      this.channelId = channel.id;
      this._joinedChannel = channel; // remember for DM calls (no guild lookup)

      // Remote media in/out.
      room.on(LK.RoomEvent.TrackSubscribed, (track, pub, participant) => {
        this.attachTrack(track, participant, false, pub.source);
      });
      room.on(LK.RoomEvent.TrackUnsubscribed, (track, pub, participant) => {
        track.detach().forEach((el) => el.remove());
        if (pub && pub.source === LK.Track.Source.ScreenShare && participant) {
          this.removeTile(participant.identity + ":screen");
        }
        this.rebuildRoster();
      });
      // Local publications (our own camera / screen preview; skip local audio to avoid echo).
      room.on(LK.RoomEvent.LocalTrackPublished, (pub, participant) => {
        if (pub.track && pub.kind === "video") this.attachTrack(pub.track, participant, true, pub.source);
        this.syncControls();
      });
      room.on(LK.RoomEvent.LocalTrackUnpublished, (pub, participant) => {
        if (pub.track) pub.track.detach().forEach((el) => el.remove());
        if (pub && pub.source === LK.Track.Source.ScreenShare && participant) {
          this.removeTile(participant.identity + ":screen");
        }
        this.rebuildRoster();
        this.syncControls();
      });
      room.on(LK.RoomEvent.ParticipantConnected, () => { this.rebuildRoster(); this.setMicEnabled(this.mic); });
      room.on(LK.RoomEvent.ParticipantDisconnected, (p) => { this.removeTile(p.identity); this.rebuildRoster(); this.setMicEnabled(this.mic); });
      room.on(LK.RoomEvent.ActiveSpeakersChanged, (speakers) => {
        this.highlightSpeakers(speakers);
        if (speakers.some((s) => s.isLocal)) afkBump(); // speaking counts as activity
      });
      room.on(LK.RoomEvent.TrackMuted, () => this.rebuildRoster());
      room.on(LK.RoomEvent.TrackUnmuted, () => this.rebuildRoster());
      room.on(LK.RoomEvent.Disconnected, () => this.cleanup());

      try {
        await room.connect(tok.url, tok.token);
        this.mic = true;
        await this.setMicEnabled(true); // no-op while alone; starts when others join
        await this.applyInputGain(); // saved input volume ≠ 100% → attach gain chain
        await this.applyInputMode(); // push-to-talk: start muted until the key is held
        this.broadcastState();       // publish our initial mute/deafen/screen state
      } catch (e) {
        toast("Could not connect to voice: " + e.message, true);
        this.cleanup();
        return;
      }
      renderVoiceConnected();
      this.rebuildRoster();
      this.syncControls();
      setVoiceStatus("Connected");
    },

    // Attach an audio/video track's element to the right place.
    attachTrack(track, participant, local, source) {
      if (track.kind === "audio") {
        if (local) return; // never play our own mic back
        const el = track.attach();
        el.autoplay = true;
        el.dataset.identity = participant.identity;
        el.muted = this.deafened || this.localMutes.has(participant.identity); // deafen / per-viewer mute
        el.volume = Math.min(1, this.volumeOf(participant.identity));          // per-viewer volume
        $("voice-audio-sink").appendChild(el);
        // route to the chosen output device
        if (el.setSinkId && media.audioOutput && media.audioOutput !== "default") {
          el.setSinkId(media.audioOutput).catch(() => {});
        }
        return;
      }
      // video — a screen share gets its OWN focusable tile, separate from the camera
      const isScreen = source === LK.Track.Source.ScreenShare;
      const tile = isScreen ? this.ensureScreenTile(participant) : this.ensureUserTile(participant);
      const el = track.attach();
      el.autoplay = true;
      el.playsInline = true;
      if (local) el.muted = true;
      tile.querySelectorAll("video").forEach((v) => v.remove());
      tile.insertBefore(el, tile.firstChild);
      tile.classList.add("has-video");
      if (isScreen && !this.focusedKey) this.focus(participant.identity + ":screen"); // auto-spotlight a new screen share
      this.rebuildRoster();
    },

    ensureUserTile(participant) {
      const id = participant.identity;
      let tile = this.tiles.get(id);
      if (tile) return tile;
      const name = participant.name || participant.identity;
      const isLocal = this.room && participant === this.room.localParticipant;
      tile = h("div", { class: "tile" + (this.localMutes.has(id) ? " local-muted" : ""),
        dataset: { key: id, identity: id }, onclick: () => this.focus(id) },
        h("div", { class: "tile-avatar" }, avatar(name, id, "lg", id)),
        h("div", { class: "tile-label" },
          h("span", { class: "name", text: name + (isLocal ? " (you)" : "") })
        )
      );
      tile.addEventListener("contextmenu", (e) => { e.preventDefault(); e.stopPropagation(); openVoiceTileMenu(id, e.clientX, e.clientY); });
      this.tiles.set(id, tile);
      $("voice-tiles").appendChild(tile);
      return tile;
    },

    ensureScreenTile(participant) {
      const key = participant.identity + ":screen";
      let tile = this.tiles.get(key);
      if (tile) return tile;
      const name = participant.name || participant.identity;
      tile = h("div", { class: "tile screen-tile", dataset: { key: key, identity: participant.identity }, onclick: () => this.focus(key) },
        h("div", { class: "tile-label" },
          h("span", { class: "screen-badge" }, icon("screen-share", 13)),
          h("span", { class: "name", text: name + "’s screen" })
        )
      );
      tile.addEventListener("contextmenu", (e) => { e.preventDefault(); e.stopPropagation(); openVoiceTileMenu(participant.identity, e.clientX, e.clientY); });
      this.tiles.set(key, tile);
      $("voice-tiles").appendChild(tile);
      return tile;
    },

    removeTile(key) {
      const tile = this.tiles.get(key);
      if (tile) { tile.remove(); this.tiles.delete(key); }
      if (this.focusedKey === key) this.focusedKey = null;
    },

    // Rebuild every tile's label (name + mute state) and prune stale tiles.
    rebuildRoster() {
      if (!this.room) return;
      const participants = [this.room.localParticipant, ...this.room.remoteParticipants.values()];
      const liveIds = new Set(participants.map((p) => p.identity));
      participants.forEach((p) => this.ensureUserTile(p));
      // prune tiles for people who left, and screen tiles whose share ended
      for (const key of [...this.tiles.keys()]) {
        const baseId = key.endsWith(":screen") ? key.slice(0, -7) : key;
        if (!liveIds.has(baseId)) { this.removeTile(key); continue; }
        if (key.endsWith(":screen") && !this.tiles.get(key).querySelector("video")) this.removeTile(key);
      }
      // update each participant's user tile (label + mute/deafen + video flag)
      const vpList = state.voicePresence[this.channelId] || [];
      participants.forEach((p) => {
        const tile = this.tiles.get(p.identity);
        if (!tile) return;
        const isLocal = p === this.room.localParticipant;
        const vp = vpList.find((x) => x.userId === p.identity) || {};
        // your own tile reflects your intent, not the alone-suppressed publish
        const muted = isLocal ? !this.mic : (this.isMuted(p) || vp.muted);
        tile.classList.toggle("has-video", !!tile.querySelector("video"));
        const label = tile.querySelector(".tile-label");
        label.innerHTML = "";
        if (vp.deafened) label.appendChild(h("span", { class: "mute-icon deaf" }, icon("headphone-off", 14)));
        else if (muted) label.appendChild(h("span", { class: "mute-icon" }, icon("mic-off", 14)));
        label.appendChild(h("span", { class: "name", text: (p.name || p.identity) + (isLocal ? " (you)" : "") }));
      });
      if (this.focusedKey && !this.tiles.has(this.focusedKey)) this.focusedKey = null;
      this.applyLayout();
      setVoiceStatus(participants.length <= 1
        ? "Connected — you're the only one here"
        : `Connected — ${participants.length} in call`);
    },

    isMuted(p) {
      // Consider muted if there is no enabled microphone publication.
      const pubs = p.audioTrackPublications ? [...p.audioTrackPublications.values()] : [];
      if (pubs.length === 0) return true;
      return pubs.every((pub) => pub.isMuted);
    },

    highlightSpeakers(speakers) {
      const speaking = new Set(speakers.map((s) => s.identity));
      for (const [id, tile] of this.tiles) tile.classList.toggle("speaking", speaking.has(id));
    },

    refreshTile() { this.rebuildRoster(); },

    // ---- spotlight / focus (Discord-style: one big pane + thumbnail strip) ----
    focus(key) {
      if (!this.tiles.has(key)) return;
      this.focusedKey = (this.focusedKey === key) ? null : key;
      this.applyLayout();
    },
    unfocus() { this.focusedKey = null; this.applyLayout(); },
    applyLayout() {
      const wrap = $("voice-tiles");
      if (!wrap) return;
      const focusing = this.focusedKey && this.tiles.has(this.focusedKey);
      wrap.classList.toggle("focused", !!focusing);
      let strip = wrap.querySelector(":scope > .voice-strip");
      if (focusing) {
        if (!strip) strip = h("div", { class: "voice-strip" });
        const stage = this.tiles.get(this.focusedKey);
        wrap.insertBefore(stage, wrap.firstChild); // stage first
        wrap.appendChild(strip);                    // strip last
        for (const [key, tile] of this.tiles) {
          const isStage = key === this.focusedKey;
          tile.classList.toggle("stage", isStage);
          tile.classList.toggle("thumb", !isStage);
          if (!isStage) strip.appendChild(tile);
          const exit = tile.querySelector(":scope > .stage-exit");
          if (isStage && !exit) {
            tile.appendChild(h("button", { class: "stage-exit", "aria-label": "Exit focus (Esc)",
              onclick: (e) => { e.stopPropagation(); this.unfocus(); } }, icon("x", 18)));
          } else if (!isStage && exit) {
            exit.remove();
          }
        }
      } else {
        for (const [, tile] of this.tiles) {
          tile.classList.remove("stage", "thumb");
          const exit = tile.querySelector(":scope > .stage-exit");
          if (exit) exit.remove();
          wrap.appendChild(tile);
        }
        if (strip) strip.remove();
      }
    },

    // Bandwidth saver (Discord-style): while you're the only person in the call
    // we don't actually publish the mic — audio only starts transmitting once at
    // least one other participant is present. `this.mic` stays the user's intent.
    hasOthers() { return !!(this.room && this.room.remoteParticipants && this.room.remoteParticipants.size >= 1); },
    async setMicEnabled(intendedOn) {
      if (!this.room) return;
      try { await this.room.localParticipant.setMicrophoneEnabled(!!intendedOn && this.hasOthers()); } catch {}
    },

    async toggleMic() {
      if (!this.room) return;
      // Un-muting the mic implicitly un-deafens (Discord behavior).
      if (!this.mic && this.deafened) return this.setDeafen(false);
      this.mic = !this.mic;
      await this.setMicEnabled(this.mic);
      this.syncControls();
      this.rebuildRoster();
      this.broadcastState();
    },
    async setDeafen(on) {
      if (!this.room) return;
      this.deafened = on;
      // Deafen mutes everyone else for you AND force-mutes your own mic.
      document.querySelectorAll("#voice-audio-sink audio").forEach((el) => {
        el.muted = on || this.localMutes.has(el.dataset.identity);
      });
      if (on && this.mic) { this.mic = false; await this.setMicEnabled(false); }
      this.syncControls();
      this.rebuildRoster();
      this.broadcastState();
    },
    toggleDeafen() { return this.setDeafen(!this.deafened); },
    async toggleCam() {
      if (!this.room) return;
      this.cam = !this.cam;
      try { await this.room.localParticipant.setCameraEnabled(this.cam); }
      catch (e) { this.cam = false; toast("Camera error: " + e.message, true); }
      this.syncControls();
    },
    async toggleScreen() {
      if (!this.room) return;
      this.screen = !this.screen;
      try {
        if (this.screen) await this.room.localParticipant.setScreenShareEnabled(true, screenCaptureOpts(), screenPublishOpts());
        else await this.room.localParticipant.setScreenShareEnabled(false);
      }
      catch (e) { this.screen = false; toast("Screen share cancelled", true); }
      this.syncControls();
      this.broadcastState();
    },

    // Tell the server (→ everyone) our self mute/deafen/screen state for the sidebar.
    broadcastState() {
      if (!this.channelId) return;
      ws.send({ type: "voice_state", channelId: this.channelId,
        muted: !this.mic, deafened: this.deafened, screensharing: this.screen });
    },

    // ---- push-to-talk: in PTT mode the mic is muted unless the key is held ----
    _pttHeld: false,
    _pttTimer: null,
    async applyInputMode() {
      if (!this.room || this.deafened) return;
      if (media.inputMode === "ptt") {
        if (this.mic && !this._pttHeld) { this.mic = false; await this.setMicEnabled(false); this.syncControls(); this.broadcastState(); }
      } else {
        if (!this.mic) { this.mic = true; await this.setMicEnabled(true); this.syncControls(); this.broadcastState(); }
      }
    },
    async pttDown() {
      if (media.inputMode !== "ptt" || !this.room || this.deafened) return;
      clearTimeout(this._pttTimer);
      this._pttHeld = true;
      if (!this.mic) { this.mic = true; await this.setMicEnabled(true); this.syncControls(); this.broadcastState(); }
    },
    pttUp() {
      if (media.inputMode !== "ptt" || !this.room) return;
      this._pttHeld = false;
      clearTimeout(this._pttTimer);
      this._pttTimer = setTimeout(async () => {
        if (this.mic && !this._pttHeld) { this.mic = false; await this.setMicEnabled(false); this.syncControls(); this.broadcastState(); }
      }, Math.max(0, Math.min(2000, media.pttRelease || 200)));
    },

    syncControls() {
      const mic = $("vc-mic"), cam = $("vc-cam"), scr = $("vc-screen"), deaf = $("vc-deafen");
      if (mic) {
        mic.innerHTML = "";
        mic.appendChild(icon(this.mic ? "mic" : "mic-off", 22));
        mic.className = "vc-btn" + (this.mic ? "" : " off");
        if (deaf) {
          deaf.innerHTML = "";
          deaf.appendChild(icon(this.deafened ? "headphone-off" : "headphones", 22));
          deaf.className = "vc-btn" + (this.deafened ? " off" : "");
        }
        cam.className = "vc-btn" + (this.cam ? " on" : "");
        scr.className = "vc-btn" + (this.screen ? " on" : "");
      }
      renderVoiceBar();
    },

    async leave() {
      if (this.room) { try { await this.room.disconnect(); } catch {} }
      this.cleanup();
    },

    cleanup() {
      this.room = null;
      this.channelId = null;
      this._gainProc = null;
      this.mic = this.cam = this.screen = false;
      this.deafened = false;
      this.tiles.clear();
      this.focusedKey = null;
      const tiles = $("voice-tiles"), sink = $("voice-audio-sink");
      if (tiles) tiles.innerHTML = "";
      if (sink) sink.innerHTML = "";
      renderVoiceBar(); // room is null now -> hides the bar
      // Re-render placeholder if the voice channel is still open.
      if (state.currentChannel && state.currentChannel.type === "voice") renderVoiceIdle(state.currentChannel);
    },
  };

  /* =======================================================================
   * 5. RENDER LAYER
   * ===================================================================== */

  // ---- auth screen ----
  function renderAuthMode() {
    const reg = state.authMode === "register";
    $("displayname-field").classList.toggle("hidden", !reg);
    $("auth-submit").textContent = reg ? "Create account" : "Log in";
    $("auth-toggle-text").textContent = reg ? "Already have an account?" : "Need an account?";
    $("auth-toggle-link").textContent = reg ? "Log in" : "Register";
    $("auth-password").setAttribute("autocomplete", reg ? "new-password" : "current-password");
    $("auth-error").textContent = "";
  }

  function showAuth() {
    $("auth-screen").classList.remove("hidden");
    $("app-shell").classList.add("hidden");
  }
  function showApp() {
    $("auth-screen").classList.add("hidden");
    $("app-shell").classList.remove("hidden");
  }

  // ---- user bar ----
  function renderUserBar() {
    const el = $("user-info");
    if (!el) return;
    el.innerHTML = "";
    if (!state.me) return;
    const mine = state.presence[state.me.id];
    const custom = mine && mine.customStatus;
    el.append(
      avatar(state.me.displayName || state.me.username, state.me.id, "", state.me.id),
      h("div", { class: "names" },
        h("div", { class: "dname", text: state.me.displayName || state.me.username }),
        h("div", { class: "uname", text: custom || ("@" + state.me.username) })
      )
    );
    el.onclick = (e) => { e.stopPropagation(); openStatusMenu(el); };
  }

  // ---- guild rail ----
  // Per-server unread + mention totals for the rail (Discord: white pill = unread,
  // red numbered badge = mentions). Muted servers/channels suppress the pill but
  // mentions still count.
  function guildUnread(g) {
    const gMuted = isMuted(g.id);
    let unread = false, mentions = 0;
    for (const c of (g.channels || [])) {
      mentions += mentionCountFor(c.id); // text + voice (text-in-voice) both count
      if (!gMuted && !isMuted(c.id) && isUnread(c.id)) unread = true;
    }
    return { unread, mentions };
  }

  function renderGuildRail() {
    const rail = $("guild-rail");
    rail.innerHTML = "";
    // Home / Direct Messages sits above the servers (like Discord's home button).
    const dmUnread = dmUnreadCount();
    const home = h("div", { class: "guild-icon action dm-home", "aria-label": "Direct Messages", onclick: enterDmMode },
      icon("message-circle", 24));
    const homeSlot = h("div", { class: "guild-slot" + (state.dmMode ? " active" : "") + (dmUnread ? " unread" : "") },
      h("span", { class: "guild-pill" }), home);
    if (dmUnread) homeSlot.appendChild(h("span", { class: "guild-mention", text: dmUnread > 99 ? "99+" : String(dmUnread) }));
    rail.appendChild(homeSlot);
    rail.appendChild(h("div", { class: "rail-divider" }));
    for (const g of state.guilds) {
      const active = state.currentGuild && state.currentGuild.id === g.id;
      const { unread, mentions } = guildUnread(g);
      const icon = h("div", {
        class: "guild-icon",
        "aria-label": g.name,
        style: `background:${colorFor(g.id)}`,
        text: initials(g.name),
        onclick: () => selectGuild(g.id),
      });
      icon.addEventListener("contextmenu", (e) => {
        e.preventDefault(); openGuildMenu(g, e.clientX, e.clientY);
      });
      const slot = h("div", {
        class: "guild-slot" + (active ? " active" : "") + (unread ? " unread" : ""),
      }, h("span", { class: "guild-pill" }), icon);
      if (mentions > 0) slot.appendChild(h("span", { class: "guild-mention", text: mentions > 99 ? "99+" : String(mentions) }));
      rail.appendChild(slot);
    }
    rail.appendChild(h("div", { class: "rail-divider" }));
    rail.appendChild(h("div", {
      class: "guild-icon action", "aria-label": "Create a server", onclick: openCreateGuildModal,
    }, icon("plus", 24)));
    rail.appendChild(h("div", {
      class: "guild-icon action", "aria-label": "Browse servers", onclick: openDiscoverModal,
    }, icon("compass", 24)));
  }

  // ---- channel sidebar ----
  function renderChannels() {
    if (state.dmMode) { renderDmSidebar(); return; }
    const g = state.currentGuild;
    const gn = $("guild-name");
    gn.textContent = g ? g.name : "ephemeral";
    gn.classList.toggle("clickable", !!g);
    gn.onclick = g ? (e) => { e.stopPropagation(); const r = gn.getBoundingClientRect(); openGuildMenu(g, r.left, r.bottom + 4); } : null;
    gn.oncontextmenu = g ? (e) => { e.preventDefault(); openGuildMenu(g, e.clientX, e.clientY); } : null;
    const list = $("channel-list");
    list.innerHTML = "";
    if (!g) return;

    const channels = [...(g.channels || [])].sort((a, b) => a.position - b.position || a.name.localeCompare(b.name));
    const text = channels.filter((c) => c.type === "text");
    const voiceCh = channels.filter((c) => c.type === "voice");

    const group = (title, items, glyphName) => {
      const header = h("div", { class: "channel-group-header" }, h("span", { text: title }));
      if (amAdmin()) {
        header.appendChild(h("button", {
          "aria-label": "Create channel",
          onclick: () => openCreateChannelModal(title === "Text Channels" ? "text" : "voice"),
        }, icon("plus", 18)));
      }
      list.appendChild(header);
      for (const c of items) {
        // "active" only when we're actually viewing this channel (voice chat counts)
        const isActive = state.currentChannel && state.currentChannel.id === c.id
          && (c.type === "text" || voiceChatOpen[c.id]);
        const mutedChan = isMuted(c.id) || isMuted(g.id);
        // Both text and voice channels have chat now (text-in-voice) → both can be unread.
        const unread = !isActive && !mutedChan && isUnread(c.id);
        const mc = mentionCountFor(c.id);
        const row = h("div", {
          class: "channel" + (isActive ? " active" : "") + (unread ? " unread" : "") + (mutedChan ? " muted" : ""),
          onclick: () => selectChannel(c.id),
        },
          h("span", { class: "glyph" }, icon(mutedChan ? "bell-off" : glyphName, 18)),
          h("span", { class: "name", text: c.name }),
          c.adminOnly ? h("span", { class: "chan-lock", "aria-label": "Admin-only channel" }, icon("lock", 13)) : null
        );
        // voice channel capacity "n/limit"
        if (c.type === "voice" && c.userLimit > 0) {
          const n = (state.voicePresence[c.id] || []).length;
          row.appendChild(h("span", { class: "chan-count" + (n >= c.userLimit ? " full" : ""), text: n + "/" + c.userLimit }));
        }
        if (unread) row.insertBefore(h("span", { class: "unread-dot" }), row.firstChild);
        if (mc > 0) row.appendChild(h("span", { class: "mention-badge", text: mc > 99 ? "99+" : String(mc) }));
        // admin edit/delete live in the right-click menu — no hover buttons
        row.addEventListener("contextmenu", (e) => {
          e.preventDefault(); openChannelMenu(c, e.clientX, e.clientY);
        });
        list.appendChild(row);

        // Voice channels: list who is currently connected, indented below.
        if (c.type === "voice") {
          const participants = state.voicePresence[c.id] || [];
          if (participants.length) {
            const presence = h("div", { class: "voice-presence" });
            for (const p of participants) {
              const icons = h("span", { class: "vp-icons" });
              if (p.screen) icons.appendChild(h("span", { class: "live-badge", text: "LIVE" }));
              if (p.deafened) icons.appendChild(h("span", { class: "vp-ic deaf" }, icon("headphone-off", 13)));
              else if (p.muted) icons.appendChild(h("span", { class: "vp-ic mute" }, icon("mic-off", 13)));
              presence.appendChild(h("div", { class: "voice-presence-user", "aria-label": p.name,
                onclick: (e) => { e.stopPropagation(); openProfileCard(p.userId, e.currentTarget); } },
                avatar(p.name, p.userId, "sm", p.userId),
                h("span", { class: "vp-name", text: p.name }),
                icons
              ));
            }
            list.appendChild(presence);
          }
        }
      }
    };

    group("Text Channels", text, "hash");
    group("Voice Channels", voiceCh, "volume");
  }

  // ---- content view switching ----
  function showView(which) {
    $("empty-view").classList.toggle("hidden", which !== "empty");
    $("chat-view").classList.toggle("hidden", which !== "chat");
    $("voice-view").classList.toggle("hidden", which !== "voice");
    // members column only makes sense in chat
    if (which !== "chat") $("member-column").classList.add("hidden");
  }

  // ---- messages ----

  // Full re-render of the message list from state.messages (ascending).
  function renderMessages() {
    const scroll = $("message-scroll");
    const list = $("message-list");
    const nearBottom = scroll.scrollHeight - scroll.scrollTop - scroll.clientHeight < 120;

    list.innerHTML = "";

    // "Load older" / start-of-history marker.
    if (state.hasMoreOlder) {
      const lo = h("div", { class: "load-older" });
      if (state.loadingOlder) lo.textContent = "Loading earlier messages…";
      else lo.append(icon("hourglass", 14), "Scroll up for earlier messages");
      list.appendChild(lo);
    } else if (state.currentChannel) {
      const _c = state.currentChannel;
      const _dm = _c.type === "dm";
      list.appendChild(h("div", { class: "chat-intro" },
        h("h2", { text: (_dm ? "" : "#") + _c.name }),
        h("p", {}, _dm
          ? "This is the start of your conversation. Messages here vanish after 7 days unless saved "
          : "This is the start of the channel. Messages here vanish after 7 days unless saved ", icon("bookmark", 13), ".")
      ));
    }

    let prev = null;
    let dividerDrawn = false;
    for (const m of state.messages) {
      // "New messages" divider before the first message newer than lastReadId
      if (!dividerDrawn && state.newDivider && m.id > state.newDivider) {
        list.appendChild(h("div", { class: "new-divider" }, h("span", { text: "New" })));
        dividerDrawn = true;
        prev = null; // don't group across the divider
      }
      const grouped =
        prev &&
        prev.authorId === m.authorId &&
        new Date(m.createdAt) - new Date(prev.createdAt) < 5 * 60 * 1000 &&
        !m.saved && !prev.saved;
      list.appendChild(renderMessage(m, grouped));
      prev = m;
    }

    if (nearBottom) scroll.scrollTop = scroll.scrollHeight;
    updateJumpPresent();
  }

  // Toggle the "Jump to present" bar based on scroll position.
  function updateJumpPresent() {
    const jp = $("jump-present");
    if (!jp) return;
    const scroll = $("message-scroll");
    const chat = state.currentChannel && state.currentChannel.type === "text";
    const nearBottom = scroll.scrollHeight - scroll.scrollTop - scroll.clientHeight < 120;
    jp.classList.toggle("hidden", nearBottom || !chat);
  }

  function renderMessage(m, grouped) {
    const mine = state.me && m.authorId === state.me.id;
    const canDelete = mine || amAdmin();
    const canPin = mine || amAdmin();
    const editing = state.editingId === m.id;
    const name = m.authorName || "Unknown";
    const mentionsMe = m.mentions && state.me && m.mentions.includes(state.me.id);

    // A reply preview forces its own header row (never grouped) so the quote reads.
    if (m.replyTo) grouped = false;

    // gutter: avatar for a group head, empty (aligned) for grouped rows —
    // no hover-timestamp (it read as buggy on hover)
    const gutter = grouped
      ? h("div", { class: "gutter" })
      : h("div", { class: "gutter" }, avatar(name, m.authorId, "", m.authorId));

    const body = h("div", { class: "body" });

    // pinned indicator
    if (m.pinned) {
      body.appendChild(h("div", { class: "pin-flag" }, icon("pin", 12, true), h("span", { text: "Pinned" })));
    }

    // quoted reply line
    if (m.replyTo) {
      const rt = m.replyTo;
      body.appendChild(h("div", {
        class: "reply-preview",
        onclick: () => jumpToMessage(rt.id),
      },
        icon("reply", 14),
        avatar(rt.authorName || "Unknown", rt.authorId, "sm"),
        h("span", { class: "rp-author", text: rt.authorName || "Unknown" }),
        h("span", { class: "rp-snippet", text: replySnippet(rt.content) })
      ));
    }

    if (!grouped) {
      const authorSpan = h("span", { class: "author", text: name,
        onclick: (e) => { e.stopPropagation(); openProfileCard(m.authorId, e.currentTarget); } });
      authorSpan.addEventListener("contextmenu", (e) => {
        e.preventDefault(); e.stopPropagation(); openUserMenu(m.authorId, e.clientX, e.clientY, authorSpan);
      });
      body.appendChild(h("div", { class: "meta" },
        authorSpan,
        h("span", { class: "time", text: relativeTime(m.createdAt) })
      ));
    }

    if (editing) {
      body.appendChild(renderEditBox(m));
    } else {
      if (m.content) {
        body.appendChild(h("div", { class: "content", html: renderMarkdown(m.content) +
          (m.editedAt ? '<span class="edited">(edited)</span>' : "") }));
      }
      if (m.attachments && m.attachments.length) {
        body.appendChild(renderAttachments(m.attachments));
      }
      if (m.content) {
        const embeds = renderEmbeds(m);
        if (embeds) body.appendChild(embeds);
      }
      if (m.reactions && m.reactions.length) {
        body.appendChild(renderReactions(m));
      }
    }

    // hover action toolbar: just react / reply / more — everything else
    // (edit, pin, save, delete, copy) lives in the More / right-click menu
    const actBtn = (ic, label, cls, onclick) => h("button", { class: cls || "", "aria-label": label, onclick }, ic);
    const actions = h("div", { class: "msg-actions" },
      actBtn(icon("smile", 16), "Add reaction", "",
        (e) => openEmojiPicker(e.currentTarget, (emoji) => reactWith(m, emoji))),
      actBtn(icon("reply", 16), "Reply", "", () => startReply(m)),
      actBtn(icon("more-horizontal", 16), "More", "",
        (e) => { const r = e.currentTarget.getBoundingClientRect(); openMessageMenu(m, r.right, r.bottom); }),
    );

    const cls = "message" + (grouped ? " grouped" : "") + (m.saved ? " saved" : "") +
      (m.pinned ? " pinned" : "") + (mentionsMe ? " mention" : "") + (m._pending ? " pending" : "");
    const row = h("div", { class: cls, dataset: { id: m.id } }, gutter, body, actions);
    row.addEventListener("contextmenu", (e) => {
      if (e.target.closest("a, button, textarea, input, .mention")) return;
      e.preventDefault();
      openMessageMenu(m, e.clientX, e.clientY);
    });
    return row;
  }

  // reaction pills below a message body
  function renderReactions(m) {
    const row = h("div", { class: "reactions" });
    for (const r of m.reactions) {
      row.appendChild(h("button", {
        class: "reaction" + (r.mine ? " mine" : ""),
        "aria-label": r.emoji,
        onclick: () => reactWith(m, r.emoji),
      },
        h("span", { class: "re-emoji", text: r.emoji }),
        h("span", { class: "re-count", text: String(r.count) })
      ));
    }
    row.appendChild(h("button", { class: "reaction add", "aria-label": "Add reaction",
      onclick: (e) => openEmojiPicker(e.currentTarget, (emoji) => reactWith(m, emoji)) }, icon("smile", 15)));
    return row;
  }

  async function reactWith(m, emoji) {
    try {
      const updated = await API.react(m.id, emoji);
      if (updated) upsertMessage(updated);
    } catch (e) { toast(e.message, true); }
  }
  async function togglePin(m) {
    try {
      const updated = m.pinned ? await API.unpin(m.id) : await API.pin(m.id);
      if (updated) upsertMessage(updated);
      toast(m.pinned ? "Message unpinned" : "Message pinned");
    } catch (e) { toast(e.message, true); }
  }

  // ---- inline edit ----
  function renderEditBox(m) {
    const ta = h("textarea", { class: "edit-box", rows: "1" });
    ta.value = m.content || "";
    ta.addEventListener("input", () => autoGrow(ta));
    ta.addEventListener("keydown", (e) => {
      if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); saveEdit(m, ta.value); }
      else if (e.key === "Escape") { e.preventDefault(); cancelEdit(); }
    });
    setTimeout(() => { ta.focus(); autoGrow(ta); const n = ta.value.length; ta.setSelectionRange(n, n); }, 0);
    const cancel = h("a", { text: "cancel", onclick: cancelEdit });
    const save = h("a", { text: "save", onclick: () => saveEdit(m, ta.value) });
    return h("div", { class: "edit-wrap" }, ta,
      h("div", { class: "edit-hint" }, "escape to ", cancel, " • enter to ", save));
  }
  function startEdit(m) { state.editingId = m.id; renderMessages(); }
  function cancelEdit() { state.editingId = null; renderMessages(); }
  async function saveEdit(m, content) {
    content = content.trim();
    if (!content) { toast("Message can't be empty", true); return; }
    if (content === (m.content || "")) { cancelEdit(); return; }
    state.editingId = null;
    try {
      const updated = await API.editMsg(m.id, content);
      upsertMessage(updated);
    } catch (e) { toast(e.message, true); renderMessages(); }
  }

  // ---- reply ----
  function replySnippet(content) {
    const s = String(content || "").replace(/\s+/g, " ").trim();
    if (!s) return "attachment";
    return s.length > 80 ? s.slice(0, 80) + "…" : s;
  }
  function startReply(m) {
    state.replyingTo = { id: m.id, authorName: m.authorName || "Unknown", content: m.content || "" };
    renderReplyBar();
    const input = $("composer-input");
    if (input) input.focus();
  }
  function cancelReply() { state.replyingTo = null; renderReplyBar(); }
  function renderReplyBar() {
    const bar = $("reply-bar");
    if (!bar) return;
    bar.innerHTML = "";
    if (!state.replyingTo) { bar.classList.add("hidden"); return; }
    bar.classList.remove("hidden");
    bar.append(
      h("div", { class: "reply-bar-info" },
        icon("reply", 14),
        h("span", { text: "Replying to " }),
        h("span", { class: "reply-bar-name", text: state.replyingTo.authorName })
      ),
      h("button", { class: "reply-bar-close", "aria-label": "Cancel reply", onclick: cancelReply }, icon("x", 16))
    );
  }
  function jumpToMessage(id) {
    const el = document.querySelector('.message[data-id="' + String(id).replace(/"/g, '\\"') + '"]');
    if (el) {
      el.scrollIntoView({ behavior: "smooth", block: "center" });
      el.classList.remove("flash");
      void el.offsetWidth; // restart animation
      el.classList.add("flash");
      setTimeout(() => el.classList.remove("flash"), 1300);
    } else {
      toast("That message isn't loaded — scroll up to find it");
    }
  }

  function isVoiceNote(a) {
    return a.contentType && a.contentType.startsWith("audio/") && a.waveform;
  }
  function renderAttachments(attachments) {
    const wrap = h("div", { class: "attachments" });
    for (const a of attachments) {
      if (isVoiceNote(a)) {
        let peaks = []; try { peaks = JSON.parse(a.waveform) || []; } catch {}
        wrap.appendChild(voicePlayer(a.url, peaks, (a.durationMs || 0) / 1000));
      } else if (isImage(a)) {
        // click opens an in-app preview; ctrl/cmd/shift-click still opens the tab
        wrap.appendChild(h("a", { class: "attach-image-link", href: a.url,
          onclick: (e) => { if (e.metaKey || e.ctrlKey || e.shiftKey) return; e.preventDefault(); openMediaPreview(a); } },
          h("img", { class: "attach-image", src: a.url, alt: a.filename, loading: "lazy" })
        ));
      } else {
        wrap.appendChild(h("a", { class: "attach-file", href: a.url, download: a.filename,
          onclick: (e) => { if (e.metaKey || e.ctrlKey || e.shiftKey) return; e.preventDefault(); openMediaPreview(a); } },
          h("span", { class: "file-icon" }, icon("file", 24)),
          h("div", { class: "file-meta" },
            h("div", { class: "file-name", text: a.filename }),
            h("div", { class: "file-size", text: formatBytes(a.sizeBytes) })
          )
        ));
      }
    }
    return wrap;
  }

  // Classify an attachment for the in-app preview (lightbox).
  function attKind(a) {
    const ct = (a.contentType || "").toLowerCase();
    if (ct.startsWith("image/")) return "image";
    if (ct.startsWith("video/")) return "video";
    if (ct.startsWith("audio/")) return "audio";
    if (ct === "application/pdf") return "pdf";
    if (ct.startsWith("text/")) return "text";
    return "file";
  }

  // In-app media preview — opens an attachment in a lightbox overlay instead of
  // a new tab. Image / video / audio / pdf / text render inline; anything else
  // gets a download card. Dismisses on backdrop click, the ✕, or Escape (the
  // global Escape handler calls closeModal, which clears #modal-root).
  function openMediaPreview(a) {
    const kind = attKind(a);
    const stage = h("div", { class: "lb-stage" });
    if (kind === "image") {
      stage.appendChild(h("img", { class: "lb-img", src: a.url, alt: a.filename }));
    } else if (kind === "video") {
      stage.appendChild(h("video", { class: "lb-video", src: a.url, controls: "", autoplay: "", playsinline: "" }));
    } else if (kind === "audio") {
      stage.appendChild(h("div", { class: "lb-card" },
        h("span", { class: "file-icon" }, icon("play", 36)),
        h("div", { class: "lb-cardname", text: a.filename }),
        h("audio", { src: a.url, controls: "", autoplay: "" })));
    } else if (kind === "pdf") {
      stage.appendChild(h("iframe", { class: "lb-frame", src: a.url, title: a.filename }));
    } else if (kind === "text") {
      const pre = h("pre", { class: "lb-text", text: "Loading preview…" });
      stage.appendChild(pre);
      fetch(a.url).then((r) => r.text()).then((t) => { pre.textContent = t.slice(0, 200000); })
        .catch(() => { pre.textContent = "Could not load preview."; });
    } else {
      stage.appendChild(h("div", { class: "lb-card" },
        h("span", { class: "file-icon" }, icon("file", 44)),
        h("div", { class: "lb-cardname", text: a.filename }),
        h("div", { class: "lb-cardsub", text: formatBytes(a.sizeBytes) }),
        h("div", { class: "lb-cardsub", text: "No preview available for this file type." })));
    }
    const bar = h("div", { class: "lb-bar" },
      h("span", { class: "lb-name", text: a.filename }),
      a.sizeBytes ? h("span", { class: "lb-size", text: formatBytes(a.sizeBytes) }) : null,
      h("a", { class: "lb-act primary", href: a.url, download: a.filename }, icon("download", 16), h("span", { text: "Download" })),
      h("a", { class: "lb-act", href: a.url, target: "_blank", rel: "noopener noreferrer" }, icon("external-link", 16), h("span", { text: "Open original" }))
    );
    const lb = h("div", { class: "lightbox", onclick: (e) => { if (e.target === lb) closeModal(); } },
      h("button", { class: "lb-close", "aria-label": "Close preview", onclick: closeModal }, icon("x", 22)),
      stage, bar
    );
    $("modal-root").innerHTML = "";
    $("modal-root").appendChild(lb);
  }

  /* =======================================================================
   * LINK EMBEDS — inline media, video-site players, OpenGraph cards.
   * Providers are a hard-coded allow-list; iframe src is always REBUILT from
   * an extracted ID, never taken from the raw user URL. Unknown links go
   * through the server's SSRF-guarded /api/unfurl for an OG card.
   * ===================================================================== */

  const EMBED_IMG = /\.(gif|png|jpe?g|webp|avif|apng)(?:[?#]|$)/i;
  const EMBED_VID = /\.(mp4|webm|mov)(?:[?#]|$)/i;
  const EMBED_AUD = /\.(mp3|ogg|wav|flac|m4a)(?:[?#]|$)/i;
  const YT_RE = /(?:youtube(?:-nocookie)?\.com\/(?:watch\?(?:[^#\s]*&)?v=|shorts\/|live\/|embed\/|v\/)|youtu\.be\/)([A-Za-z0-9_-]{11})/i;
  const VIMEO_RE = /(?:player\.)?vimeo\.com\/(?:video\/)?(\d+)(?:[/?#]|$)/i;
  const SPOTIFY_RE = /open\.spotify\.com\/(track|album|playlist|episode|show|artist)\/([A-Za-z0-9]+)/i;
  const TWITCH_VOD_RE = /twitch\.tv\/videos\/(\d+)/i;
  const TWITCH_CHAN_RE = /twitch\.tv\/([A-Za-z0-9_]{3,25})(?:[/?#]|$)/i;

  const unfurlCache = new Map();   // url -> { done, dto, promise }
  const activeEmbeds = new Set();  // "msgId|url" facades the user clicked (survive re-renders)

  function extractEmbedUrls(raw) {
    if (!raw) return [];
    // never embed links inside code spans/blocks
    const stripped = String(raw).replace(/```[\s\S]*?```/g, " ").replace(/`[^`\n]*`/g, " ");
    const found = stripped.match(/https?:\/\/[^\s<]+[^\s<.,;:!?)]/g) || [];
    return [...new Set(found)].slice(0, 3); // cap per message
  }

  function safeHttpUrl(u) { return typeof u === "string" && /^https?:\/\//i.test(u) ? u : null; }

  function providerIframe(src, ratio, height) {
    const f = h("iframe", {
      class: "embed-iframe", src, loading: "lazy",
      referrerpolicy: "strict-origin-when-cross-origin",
      allow: "accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share; fullscreen",
      sandbox: "allow-scripts allow-same-origin allow-popups allow-popups-to-escape-sandbox allow-presentation",
    });
    f.setAttribute("allowfullscreen", "");
    if (ratio) f.style.aspectRatio = ratio;
    if (height) f.style.height = height + "px";
    return f;
  }

  function renderEmbeds(m) {
    const urls = extractEmbedUrls(m.content);
    if (!urls.length) return null;
    const wrap = h("div", { class: "embeds" });
    for (const u of urls) {
      const el = renderOneEmbed(m, u);
      if (el) wrap.appendChild(el);
    }
    return wrap.childNodes.length ? wrap : null;
  }

  function renderOneEmbed(m, url) {
    const key = m.id + "|" + url;

    if (EMBED_IMG.test(url)) {
      return h("a", { class: "embed-media", href: url, target: "_blank", rel: "noopener noreferrer" },
        h("img", { class: "embed-img", src: url, loading: "lazy", referrerpolicy: "no-referrer", alt: "" }));
    }
    if (EMBED_VID.test(url)) {
      return h("video", { class: "embed-video", src: url, controls: "", preload: "metadata", playsinline: "" });
    }
    if (EMBED_AUD.test(url)) {
      return h("audio", { class: "embed-audio", src: url, controls: "", preload: "metadata" });
    }

    const yt = url.match(YT_RE);
    if (yt) {
      const id = yt[1];
      const embed = "https://www.youtube-nocookie.com/embed/" + id;
      if (activeEmbeds.has(key)) {
        return h("div", { class: "embed-frame-wrap" }, providerIframe(embed + "?autoplay=1", "16/9"));
      }
      // click-to-play facade: nothing loads from Google until the user clicks
      const b = h("button", {
        class: "yt-facade", type: "button", "aria-label": "Play video",
        onclick: () => {
          activeEmbeds.add(key);
          b.replaceWith(h("div", { class: "embed-frame-wrap" }, providerIframe(embed + "?autoplay=1", "16/9")));
        },
      }, h("span", { class: "yt-play" }));
      b.style.backgroundImage = `url(https://i.ytimg.com/vi/${id}/hqdefault.jpg)`;
      return b;
    }

    const vim = url.match(VIMEO_RE);
    if (vim) {
      return h("div", { class: "embed-frame-wrap" },
        providerIframe("https://player.vimeo.com/video/" + vim[1], "16/9"));
    }
    const sp = url.match(SPOTIFY_RE);
    if (sp) {
      return h("div", { class: "embed-frame-wrap spotify" },
        providerIframe("https://open.spotify.com/embed/" + sp[1] + "/" + sp[2], null, sp[1] === "track" ? 152 : 352));
    }
    const tv = url.match(TWITCH_VOD_RE);
    if (tv) {
      return h("div", { class: "embed-frame-wrap" },
        providerIframe("https://player.twitch.tv/?video=" + tv[1] + "&parent=" + location.hostname + "&autoplay=false", "16/9"));
    }
    const tc = url.match(TWITCH_CHAN_RE);
    if (tc && tc[1].toLowerCase() !== "videos") {
      return h("div", { class: "embed-frame-wrap" },
        providerIframe("https://player.twitch.tv/?channel=" + tc[1] + "&parent=" + location.hostname + "&autoplay=false", "16/9"));
    }

    return renderUnfurlCard(url);
  }

  // Unknown link -> ask the server for an OG card (cached; re-render safe).
  function renderUnfurlCard(url) {
    let entry = unfurlCache.get(url);
    if (!entry) {
      entry = { done: false, dto: null };
      entry.promise = API.unfurl(url)
        .then((dto) => { entry.done = true; entry.dto = dto; return dto; })
        .catch(() => { entry.done = true; entry.dto = null; return null; });
      unfurlCache.set(url, entry);
    }
    if (entry.done) {
      return entry.dto ? buildUnfurlCard(entry.dto, url) : null;
    }
    const holder = h("div", { class: "embed-card-holder" });
    entry.promise.then((dto) => {
      if (!holder.isConnected) return;
      if (dto) holder.replaceWith(buildUnfurlCard(dto, url));
      else holder.remove();
    });
    return holder;
  }

  function buildUnfurlCard(dto, url) {
    const card = h("a", { class: "embed-card", href: url, target: "_blank", rel: "noopener noreferrer" });
    card.style.borderLeftColor =
      dto.themeColor && /^#[0-9a-fA-F]{3,8}$/.test(dto.themeColor) ? dto.themeColor : "var(--accent)";
    const txt = h("div", { class: "ec-text" });
    if (dto.siteName) txt.appendChild(h("div", { class: "ec-site", text: dto.siteName }));
    if (dto.title) txt.appendChild(h("div", { class: "ec-title", text: dto.title }));
    if (dto.description) txt.appendChild(h("div", { class: "ec-desc", text: dto.description }));
    card.appendChild(txt);
    const img = safeHttpUrl(dto.imageUrl);
    if (img) card.appendChild(h("img", { class: "ec-thumb", src: img, loading: "lazy", referrerpolicy: "no-referrer", alt: "" }));
    return card;
  }

  /* ------------------------------ GIF picker ---------------------------- */

  function openGifPicker(anchor) {
    const search = h("input", { class: "text-input emoji-search", placeholder: "Search Tenor…" });
    const grid = h("div", { class: "gif-grid" });
    const card = h("div", { class: "gif-picker" }, search, grid);
    popover(anchor, card);
    let seq = 0;
    const load = async (q) => {
      const my = ++seq;
      grid.innerHTML = "";
      grid.appendChild(h("div", { class: "gif-status", text: "Loading…" }));
      try {
        const res = q ? await API.gifSearch(q) : await API.gifFeatured();
        if (my !== seq) return;
        grid.innerHTML = "";
        if (!res || !res.results || !res.results.length) {
          grid.appendChild(h("div", { class: "gif-status", text: "No results" }));
          return;
        }
        for (const g of res.results) {
          const src = safeHttpUrl(g.preview), full = safeHttpUrl(g.url);
          if (!src || !full) continue;
          grid.appendChild(h("button", { class: "gif-cell", "aria-label": "Send GIF",
            onclick: () => { closePopover(); sendGif(full); } },
            h("img", { src, loading: "lazy", alt: "" })));
        }
      } catch (e) {
        if (my !== seq) return;
        grid.innerHTML = "";
        grid.appendChild(h("div", { class: "gif-status",
          text: e.status === 404
            ? "GIF search isn't configured on this server (set ephemeral.tenor-key). Pasting any .gif link still embeds it."
            : "GIF search failed: " + e.message }));
      }
    };
    let t = null;
    search.addEventListener("input", () => { clearTimeout(t); t = setTimeout(() => load(search.value.trim()), 300); });
    setTimeout(() => search.focus(), 0);
    load("");
  }

  async function sendGif(url) {
    if (!state.currentChannel || state.currentChannel.type !== "text") return;
    try {
      const msg = await API.send(state.currentChannel.id, url, [], null);
      onIncomingMessage(msg);
    } catch (e) { toast(e.message, true); }
  }

  /* =======================================================================
   * VOICE MESSAGES — record → compute a waveform → send as an audio attachment,
   * played back with a seekable waveform.
   * ===================================================================== */
  const fmtTime = (s) => { s = Math.max(0, Math.floor(s || 0)); return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0"); };
  function pickAudioMime() {
    const c = ["audio/webm;codecs=opus", "audio/webm", "audio/ogg;codecs=opus", "audio/mp4;codecs=mp4a.40.2", "audio/mp4"];
    for (const t of c) if (window.MediaRecorder && MediaRecorder.isTypeSupported(t)) return t;
    return "";
  }
  async function computePeaks(blob, bars = 56) {
    const buf = await blob.arrayBuffer();
    const AC = window.AudioContext || window.webkitAudioContext;
    const ctx = new AC();
    let audio;
    try { audio = await ctx.decodeAudioData(buf.slice(0)); } finally { /* keep */ }
    const len = audio.length, block = Math.floor(len / bars) || 1;
    const peaks = new Array(bars).fill(0);
    for (let b = 0; b < bars; b++) {
      const start = b * block, end = Math.min(start + block, len);
      let sumSq = 0, n = 0;
      for (let ch = 0; ch < audio.numberOfChannels; ch++) {
        const d = audio.getChannelData(ch);
        for (let i = start; i < end; i++) { const v = d[i]; sumSq += v * v; n++; }
      }
      peaks[b] = Math.sqrt(sumSq / Math.max(1, n));
    }
    const max = Math.max(...peaks) || 1;
    const duration = audio.duration;
    try { await ctx.close(); } catch {}
    return { peaks: peaks.map((p) => Math.round((p / max) * 100)), duration };
  }
  function drawWaveform(canvas, peaks, progress, opts) {
    opts = opts || {};
    const dpr = window.devicePixelRatio || 1, W = canvas.clientWidth || 200, H = canvas.clientHeight || 32;
    if (canvas.width !== W * dpr || canvas.height !== H * dpr) { canvas.width = W * dpr; canvas.height = H * dpr; }
    const ctx = canvas.getContext("2d"); ctx.setTransform(dpr, 0, 0, dpr, 0, 0); ctx.clearRect(0, 0, W, H);
    const cs = getComputedStyle(document.documentElement);
    const played = (opts.playedColor || cs.getPropertyValue("--accent")).trim();
    const unplayed = (opts.unplayedColor || cs.getPropertyValue("--text-faint")).trim();
    const bw = 3, gap = 2, step = bw + gap, n = Math.max(1, Math.min(peaks.length, Math.floor(W / step))), mid = H / 2;
    const cut = progress * n;
    for (let i = 0; i < n; i++) {
      const p = (peaks[Math.floor(i / n * peaks.length)] || 0) / 100;
      const hgt = Math.max(2, p * H), x = i * step, y = mid - hgt / 2;
      ctx.fillStyle = i < cut ? played : unplayed;
      if (ctx.roundRect) { ctx.beginPath(); ctx.roundRect(x, y, bw, hgt, 1.5); ctx.fill(); }
      else ctx.fillRect(x, y, bw, hgt);
    }
  }
  // A seekable voice-message player attached under a message bubble.
  function voicePlayer(src, peaks, durationSec) {
    const audio = new Audio(); audio.preload = "metadata"; audio.src = src;
    const canvas = h("canvas");
    const wave = h("div", { class: "vm-wave", role: "slider", tabindex: "0", "aria-label": "Seek voice message",
      "aria-valuemin": "0", "aria-valuemax": String(Math.round(durationSec || 0)), "aria-valuenow": "0" }, canvas);
    const btn = h("button", { class: "vm-play", "aria-label": "Play voice message" }, icon("play", 16));
    const time = h("span", { class: "vm-time", text: fmtTime(durationSec) });
    const root = h("div", { class: "vm" }, btn, wave, time);
    const render = (p) => {
      drawWaveform(canvas, peaks, isFinite(p) ? p : 0);
      const cur = (isFinite(p) ? p : 0) * (durationSec || 0);
      time.textContent = fmtTime(cur || durationSec);
      wave.setAttribute("aria-valuenow", String(Math.round(cur)));
      wave.setAttribute("aria-valuetext", fmtTime(cur) + " of " + fmtTime(durationSec));
    };
    const dur = () => (isFinite(audio.duration) && audio.duration > 0 ? audio.duration : (durationSec || 0));
    const loop = () => { if (audio.paused) return; render(audio.currentTime / (dur() || 1)); requestAnimationFrame(loop); };
    btn.onclick = () => { audio.paused ? audio.play() : audio.pause(); };
    audio.onplay = () => { btn.innerHTML = ""; btn.appendChild(icon("pause", 16)); btn.setAttribute("aria-label", "Pause"); loop(); };
    audio.onpause = () => { btn.innerHTML = ""; btn.appendChild(icon("play", 16)); btn.setAttribute("aria-label", "Play"); };
    audio.onended = () => { audio.currentTime = 0; render(0); };
    const seek = (clientX) => { const r = wave.getBoundingClientRect(); const ratio = Math.min(1, Math.max(0, (clientX - r.left) / r.width)); audio.currentTime = ratio * (dur() || 0); render(ratio); };
    wave.onpointerdown = (e) => { wave.setPointerCapture(e.pointerId); seek(e.clientX);
      const mv = (ev) => seek(ev.clientX); const up = () => { wave.removeEventListener("pointermove", mv); wave.removeEventListener("pointerup", up); };
      wave.addEventListener("pointermove", mv); wave.addEventListener("pointerup", up); };
    wave.onkeydown = (e) => { const t = audio.currentTime;
      if (e.key === " " || e.key === "Enter") btn.onclick();
      else if (e.key === "ArrowRight") audio.currentTime = Math.min(dur(), t + 5);
      else if (e.key === "ArrowLeft") audio.currentTime = Math.max(0, t - 5);
      else return;
      e.preventDefault(); render(audio.currentTime / (dur() || 1)); };
    // draw once laid out
    requestAnimationFrame(() => render(0));
    return root;
  }

  // ---- recording UI in the composer ----
  let rec = null; // { mediaRecorder, stream, chunks, startedAt, timer, meterStop, canvas }
  async function startVoiceRecording() {
    if (rec) return;
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) { toast("Recording needs a secure page (https/localhost)", true); return; }
    let stream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true } });
    } catch (e) {
      const msg = e.name === "NotAllowedError" ? "Microphone permission denied"
        : e.name === "NotFoundError" ? "No microphone found" : "Couldn't access the mic";
      toast(msg, true); return;
    }
    const mime = pickAudioMime();
    let mr;
    try { mr = new MediaRecorder(stream, mime ? { mimeType: mime, audioBitsPerSecond: 32000 } : { audioBitsPerSecond: 32000 }); }
    catch { stream.getTracks().forEach((t) => t.stop()); toast("Recording isn't supported in this browser", true); return; }
    const chunks = [];
    mr.ondataavailable = (e) => { if (e.data && e.data.size) chunks.push(e.data); };
    const startedAt = performance.now();
    rec = { mr, stream, chunks, startedAt, timer: null, meterStop: null };
    rec.timer = setTimeout(() => { if (rec) finishVoiceRecording(true); }, 120000); // 2-min cap
    mr.start(1000);
    renderRecordingBar();
  }
  async function finishVoiceRecording(send) {
    if (!rec) return;
    const r = rec; rec = null;
    clearTimeout(r.timer); stopMeterFor(r);
    const done = new Promise((resolve) => { r.mr.onstop = resolve; });
    try { r.mr.stop(); } catch {}
    await done;
    r.stream.getTracks().forEach((t) => t.stop());
    $("recording-bar") && $("recording-bar").classList.add("hidden");
    if (!send) return;
    const blob = new Blob(r.chunks, { type: r.mr.mimeType || "audio/webm" });
    if (!blob.size) { toast("Nothing recorded", true); return; }
    let peaks = [], duration = (performance.now() - r.startedAt) / 1000;
    try { const p = await computePeaks(blob); peaks = p.peaks; duration = p.duration || duration; } catch {}
    await sendVoiceMessage(blob, peaks, Math.round(duration * 1000));
  }
  function stopMeterFor(r) { if (r && r._meterStop) { try { r._meterStop(); } catch {} r._meterStop = null; } }
  async function sendVoiceMessage(blob, peaks, durationMs) {
    if (!state.currentChannel) return;
    const ext = (blob.type || "").includes("mp4") ? "m4a" : (blob.type || "").includes("ogg") ? "ogg" : "webm";
    const file = new File([blob], "voice-message." + ext, { type: blob.type });
    try {
      const att = await API.uploadVoice(file, durationMs, JSON.stringify(peaks));
      const msg = await API.send(state.currentChannel.id, "", [att.id], null);
      onIncomingMessage(msg);
    } catch (e) { toast("Couldn't send voice message: " + e.message, true); }
  }
  function renderRecordingBar() {
    let bar = $("recording-bar");
    if (!bar) {
      bar = h("div", { class: "recording-bar", id: "recording-bar" });
      const wrap = $("composer-input").closest(".composer-wrap") || document.body;
      wrap.insertBefore(bar, wrap.firstChild);
    }
    bar.classList.remove("hidden");
    bar.innerHTML = "";
    const dot = h("span", { class: "rec-dot" });
    const time = h("span", { class: "rec-time", text: "0:00" });
    const canvas = h("canvas", { class: "rec-meter" });
    const cancel = h("button", { class: "rec-btn cancel", "aria-label": "Cancel", onclick: () => finishVoiceRecording(false) }, icon("trash", 16));
    const send = h("button", { class: "rec-btn send", "aria-label": "Send voice message", onclick: () => finishVoiceRecording(true) }, icon("send", 16));
    bar.append(dot, time, canvas, cancel, send);
    // live meter (respect reduced-motion)
    const reduce = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const t0 = performance.now();
    const tick = () => { if (!rec) return; time.textContent = fmtTime((performance.now() - t0) / 1000); };
    const timeInt = setInterval(tick, 250);
    if (!reduce) {
      try {
        const AC = window.AudioContext || window.webkitAudioContext;
        const actx = new AC(), srcNode = actx.createMediaStreamSource(rec.stream), an = actx.createAnalyser();
        an.fftSize = 1024; srcNode.connect(an);
        const td = new Uint8Array(an.fftSize); const hist = [];
        let raf;
        const draw = () => {
          an.getByteTimeDomainData(td);
          let s = 0; for (let i = 0; i < td.length; i++) { const v = (td[i] - 128) / 128; s += v * v; }
          hist.push(Math.min(100, Math.sqrt(s / td.length) * 300)); if (hist.length > 56) hist.shift();
          drawWaveform(canvas, hist, 1);
          raf = requestAnimationFrame(draw);
        };
        draw();
        rec._meterStop = () => { cancelAnimationFrame(raf); clearInterval(timeInt); try { srcNode.disconnect(); actx.close(); } catch {} };
      } catch { rec._meterStop = () => clearInterval(timeInt); }
    } else { rec._meterStop = () => clearInterval(timeInt); }
  }

  // ---- typing indicator ----
  function showTyping(userId, name) {
    const existing = state.typing.get(userId);
    if (existing) clearTimeout(existing.timeout);
    const timeout = setTimeout(() => { state.typing.delete(userId); renderTyping(); }, 5000);
    state.typing.set(userId, { name, timeout });
    renderTyping();
  }
  function clearTyping() {
    for (const t of state.typing.values()) clearTimeout(t.timeout);
    state.typing.clear();
    renderTyping();
  }
  function renderTyping() {
    const el = $("typing-indicator");
    const names = [...state.typing.values()].map((t) => t.name);
    if (names.length === 0) { el.innerHTML = ""; return; }
    let text;
    if (names.length === 1) text = `${names[0]} is typing…`;
    else if (names.length === 2) text = `${names[0]} and ${names[1]} are typing…`;
    else if (names.length === 3) text = `${names[0]}, ${names[1]} and ${names[2]} are typing…`;
    else text = "Several people are typing…";
    el.innerHTML = "";
    el.append(
      h("span", { class: "dots" }, h("span"), h("span"), h("span")),
      h("span", { text })
    );
  }

  // ---- members ----
  function renderMembers() {
    const col = $("member-column");
    const list = $("member-list");
    $("member-count").textContent = state.members.length;
    list.innerHTML = "";

    // admin add box
    $("member-add").classList.toggle("hidden", !amAdmin());

    const sorted = [...state.members].sort((a, b) => {
      if (a.role !== b.role) return a.role === "admin" ? -1 : 1;
      return (a.displayName || a.username).localeCompare(b.displayName || b.username);
    });

    for (const m of sorted) {
      const isMe = state.me && m.userId === state.me.id;
      const row = h("div", { class: "member-row" },
        avatar(m.displayName || m.username, m.userId, "", m.userId),
        h("div", { class: "m-names",
          onclick: (e) => { e.stopPropagation(); openProfileCard(m.userId, e.currentTarget); } },
          h("div", { class: "m-dname" + (m.role === "admin" ? " admin" : ""),
            text: (m.displayName || m.username) + (isMe ? " (you)" : "") }),
          h("div", { class: "m-uname", text: "@" + m.username })
        )
      );
      row.addEventListener("contextmenu", (e) => {
        e.preventDefault(); openUserMenu(m.userId, e.clientX, e.clientY, row);
      });
      if (m.role === "admin") row.appendChild(h("span", { class: "role-badge admin", text: "admin" }));
      // admin promote/demote/kick live in the right-click menu — no hover buttons
      list.appendChild(row);
    }
    applyMembersVisibility();
  }

  // Decide whether the members column should be visible right now: honor an
  // explicit user toggle, otherwise auto-hide on windows too narrow to show it
  // without cramping the chat (which would force a horizontal squeeze of the
  // message area). Re-run on channel select and on window resize.
  const MEMBERS_MIN_VW = 1200;   // members auto-show only at/above this width
  let membersUserPref = null;    // null = auto; true/false = explicit user choice
  function membersAutoShow() { return window.innerWidth >= MEMBERS_MIN_VW; }
  function applyMembersVisibility() {
    const col = $("member-column");
    const inChat = !$("chat-view").classList.contains("hidden");
    const show = inChat && (membersUserPref === null ? membersAutoShow() : membersUserPref);
    col.classList.toggle("hidden", !show);
    const btn = $("toggle-members-btn");
    if (btn) btn.classList.toggle("active", show);
  }

  // ---- voice view ----
  // Voice view header: glyph + name + an "Open Chat" button (text-in-voice).
  function renderVoiceHeader(channel) {
    const el = $("voice-channel-name");
    el.innerHTML = "";
    if (channel.dm) {
      const av = avatar(channel.name, channel.other && channel.other.id, "sm");
      el.append(av, h("span", { class: "ch-name", text: channel.name }),
        h("button", { class: "ch-toggle", "aria-label": "Back to messages",
          onclick: () => { if (state.currentDm) selectDm(state.currentDm); } }, icon("hash", 15), h("span", { text: "Chat" })));
      return;
    }
    el.append(h("span", { class: "glyph" }, icon("volume", 20)), h("span", { class: "ch-name", text: channel.name }),
      h("button", { class: "ch-toggle", "aria-label": "Open text chat",
        onclick: () => { voiceChatOpen[channel.id] = true; selectChannel(channel.id); } }, icon("hash", 15), h("span", { text: "Chat" })));
  }
  // Brief "Connecting…" pane shown while auto-joining a voice channel on click.
  function renderVoiceConnecting(channel) {
    renderVoiceHeader(channel);
    $("voice-controls").classList.add("hidden");
    $("voice-tiles").classList.add("hidden");
    setVoiceStatus("Connecting…");
    const ph = $("voice-placeholder");
    ph.classList.remove("hidden");
    ph.innerHTML = "";
    ph.append(
      h("div", { class: "big-glyph" }, icon("volume", 64)),
      h("h2", { text: channel.name }),
      h("p", { text: "Connecting…" })
    );
  }
  function renderVoiceIdle(channel) {
    renderVoiceHeader(channel);
    $("voice-controls").classList.add("hidden");
    $("voice-tiles").classList.add("hidden");
    setVoiceStatus("");
    const ph = $("voice-placeholder");
    ph.classList.remove("hidden");
    ph.innerHTML = "";
    if (!voice.available()) {
      ph.append(
        h("div", { class: "big-glyph" }, icon("mic-off", 64)),
        h("h2", { text: "Voice unavailable" }),
        h("p", { text: "The LiveKit client could not be loaded, so calls are disabled." })
      );
      return;
    }
    ph.append(
      h("div", { class: "big-glyph" }, icon("volume", 64)),
      h("h2", { text: channel.name }),
      h("p", { text: "Voice, video and screen share for this channel." }),
      h("button", { class: "btn", text: "Join Voice", onclick: () => voice.join(channel) })
    );
  }
  function renderVoiceConnected() {
    const ch = (state.currentGuild && (state.currentGuild.channels || []).find((c) => c.id === voice.channelId)) || voice._joinedChannel;
    if (ch) renderVoiceHeader(ch);
    $("voice-placeholder").classList.add("hidden");
    $("voice-tiles").classList.remove("hidden");
    $("voice-controls").classList.remove("hidden");
    renderVoiceBar();
  }

  // Persistent "you're in a call" bar in the sidebar — visible on every view so
  // you can mute or disconnect from anywhere, and click to return to the call.
  function renderVoiceBar() {
    const bar = $("voice-bar");
    if (!bar) return;
    if (!voice.room) { bar.classList.add("hidden"); bar.innerHTML = ""; return; }
    const jc = voice._joinedChannel;
    const isDm = !!(jc && jc.dm);
    const chan = state.currentGuild && (state.currentGuild.channels || []).find((c) => c.id === voice.channelId);
    bar.classList.remove("hidden");
    bar.innerHTML = "";
    const label = isDm ? jc.name : (state.currentGuild && chan ? state.currentGuild.name : "In a call");
    const returnToCall = () => {
      if (isDm) { showView("voice"); renderVoiceConnected(); }
      else if (chan) selectChannel(chan.id);
    };
    bar.append(
      h("div", { class: "vbar-info", "aria-label": "Return to call", onclick: returnToCall },
        h("span", { class: "vbar-status" }, icon("volume", 13), h("span", { text: " Voice Connected" })),
        h("span", { class: "vbar-channel", text: label })
      ),
      h("div", { class: "vbar-actions" },
        h("button", { class: "vbar-btn" + (voice.mic ? "" : " off"), "aria-label": "Mute / unmute",
          onclick: (e) => { e.stopPropagation(); voice.toggleMic(); } }, icon(voice.mic ? "mic" : "mic-off", 16)),
        h("button", { class: "vbar-btn" + (voice.deafened ? " off" : ""), "aria-label": "Deafen / undeafen",
          onclick: (e) => { e.stopPropagation(); voice.toggleDeafen(); } }, icon(voice.deafened ? "headphone-off" : "headphones", 16)),
        h("button", { class: "vbar-btn leave", "aria-label": "Disconnect",
          onclick: (e) => { e.stopPropagation(); voice.leave(); } }, icon("phone-off", 16))
      )
    );
  }
  function setVoiceStatus(text) { const el = $("voice-status"); if (el) el.textContent = text; }

  /* ======================================================================
   * DIRECT MESSAGES — guild-less 1:1 conversations (text + calls). They reuse
   * the chat view, the message pipeline and the voice/LiveKit layer; only
   * membership and discovery differ. A DM presents to the rest of the app as a
   * synthetic channel object of type "dm".
   * ==================================================================== */
  function dmChannelObj(dm) {
    return { id: dm.channelId, type: "dm", dm: true,
      name: dm.other.displayName || dm.other.username, other: dm.other };
  }
  function dmUnreadCount() { return (state.dms || []).filter((d) => d.unread).length; }

  async function loadDms() {
    try { state.dms = await API.listDms(); } catch { state.dms = state.dms || []; }
    ws.subscribeDms((state.dms || []).map((d) => d.channelId));
    if (state.dmMode) renderDmSidebar();
    renderGuildRail();
  }

  function enterDmMode() {
    state.dmMode = true;
    state.currentGuild = null;
    $("member-column").classList.add("hidden");
    $("content").classList.remove("hide-mobile");
    renderGuildRail();
    renderDmSidebar();
    if (state.currentDm) selectDm(state.currentDm);
    else showView("empty");
    loadDms();
  }

  function renderDmSidebar() {
    const gn = $("guild-name");
    gn.textContent = "Direct Messages";
    gn.classList.remove("clickable");
    gn.onclick = null; gn.oncontextmenu = null;
    const list = $("channel-list");
    list.innerHTML = "";
    const head = h("div", { class: "channel-group-header" }, h("span", { text: "Direct Messages" }),
      h("button", { "aria-label": "New DM", onclick: openNewDmModal }, icon("plus", 18)));
    list.appendChild(head);
    const dms = state.dms || [];
    if (!dms.length) {
      list.appendChild(h("div", { class: "dm-empty",
        text: "No conversations yet. Start one from someone's profile, or with New DM." }));
      return;
    }
    for (const dm of dms) {
      const active = state.currentDm && state.currentDm.channelId === dm.channelId;
      const av = avatar(dm.other.displayName || dm.other.username, dm.other.id, "sm");
      av.classList.add("has-status"); av.appendChild(statusDot(dm.other.id));
      const row = h("div", {
        class: "channel dm-row" + (active ? " active" : "") + (dm.unread && !active ? " unread" : ""),
        onclick: () => selectDm(dm),
      }, av, h("span", { class: "name", text: dm.other.displayName || dm.other.username }),
        (dm.unread && !active) ? h("span", { class: "dm-dot" }) : null);
      list.appendChild(row);
    }
  }

  async function selectDm(dm) {
    state.dmMode = true;
    state.currentDm = dm;
    state.currentGuild = null;
    state.currentChannel = dmChannelObj(dm);
    dm.unread = false;
    $("member-column").classList.add("hidden");
    $("content").classList.remove("hide-mobile");
    showView("chat");
    renderDmHeader(dm);
    clearTyping(); closeMention(); cancelReply();
    renderGuildRail(); renderDmSidebar();
    await loadMessages(dm.channelId);
    ackCurrentLatest();
  }

  function renderDmHeader(dm) {
    const el = $("chat-channel-name");
    el.innerHTML = "";
    const av = avatar(dm.other.displayName || dm.other.username, dm.other.id, "sm");
    av.classList.add("has-status"); av.appendChild(statusDot(dm.other.id));
    el.append(av, h("span", { class: "ch-name", text: dm.other.displayName || dm.other.username }),
      h("button", { class: "ch-call", "aria-label": "Start voice call", onclick: () => startDmCall(false) }, icon("phone", 17)),
      h("button", { class: "ch-call", "aria-label": "Start video call", onclick: () => startDmCall(true) }, icon("video", 17)));
  }

  async function startDmCall(video) {
    const dm = state.currentDm;
    if (!dm) return;
    if (!voice.available()) { toast("Voice is unavailable", true); return; }
    const ch = dmChannelObj(dm);
    showView("voice");
    renderVoiceConnecting(ch);
    await voice.join(ch);
    if (video && voice.room && !voice.cam) { try { await voice.toggleCam(); } catch {} }
  }

  function markDmUnread(channelId) {
    const dm = (state.dms || []).find((x) => x.channelId === channelId);
    if (!dm) return;
    if (state.currentDm && state.currentDm.channelId === channelId
        && !$("chat-view").classList.contains("hidden")) return; // we're looking at it
    dm.unread = true;
    if (state.dmMode) renderDmSidebar();
    renderGuildRail();
  }

  async function openDmWithUser(userId) {
    try {
      const dm = await API.openDm(userId);
      state.dms = state.dms || [];
      const i = state.dms.findIndex((d) => d.channelId === dm.channelId);
      if (i >= 0) state.dms[i] = dm; else state.dms.unshift(dm);
      ws.subscribeDms([dm.channelId]);
      closePopover(); closeModal();
      selectDm(dm);
    } catch (e) { toast(e.message, true); }
  }

  function openNewDmModal() {
    const input = h("input", { class: "text-input", placeholder: "username", autocomplete: "off" });
    const err = h("div", { class: "modal-error" });
    const submit = async () => {
      const uname = input.value.trim().replace(/^@/, "");
      if (!uname) { err.textContent = "Enter a username"; return; }
      try {
        const dm = await API.openDmByUsername(uname);
        state.dms = state.dms || [];
        if (!state.dms.find((d) => d.channelId === dm.channelId)) state.dms.unshift(dm);
        ws.subscribeDms([dm.channelId]);
        closeModal();
        selectDm(dm);
      } catch (e) { err.textContent = e.message; }
    };
    input.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); submit(); } });
    modal({
      title: "New Direct Message",
      subtitle: "Message someone by their username.",
      body: [h("label", {}, "Username", input), err],
      footer: [h("button", { class: "btn btn-secondary", text: "Cancel", onclick: closeModal }),
        h("button", { class: "btn", text: "Message", onclick: submit })],
    });
  }

  /* =======================================================================
   * 6. ACTIONS — user intents
   * ===================================================================== */

  // ---- bootstrap after we have a token ----
  async function bootstrapSession() {
    try {
      state.me = await API.me();
    } catch (e) {
      handleLogout(false);
      return;
    }
    ensureMyPresence();
    await syncSettingsFromServer(); // restore persisted settings + mutes before rendering
    showApp();
    renderUserBar();
    ws.connect();
    await loadGuilds();
    await loadDms();
    updateTitleBadge();
    handleDeepLink();
  }

  async function loadGuilds() {
    try {
      state.guilds = await API.myGuilds();
    } catch (e) { toast(e.message, true); return; }
    // Subscribe to every server + pull unread for all of them, so the rail shows
    // notifications for servers we aren't currently looking at.
    ws.subscribeGuilds(state.guilds.map((g) => g.id));
    await loadAllReadState();
    renderGuildRail();
    if (state.guilds.length) {
      // Prefer the currently-open guild if it still exists.
      const keep = state.currentGuild && state.guilds.find((g) => g.id === state.currentGuild.id);
      selectGuild((keep || state.guilds[0]).id);
    } else {
      state.currentGuild = null;
      state.currentChannel = null;
      renderChannels();
      showView("empty");
    }
  }

  async function selectGuild(id) {
    const g = state.guilds.find((x) => x.id === id);
    if (!g) return;
    state.dmMode = false;
    state.currentDm = null;
    state.currentGuild = g;
    state.currentChannel = null;

    // Fetch membership so admin affordances render correctly.
    try {
      state.members = await API.members(g.id);
      const mine = state.me && state.members.find((m) => m.userId === state.me.id);
      state.myRole = mine ? mine.role : (g.ownerId === state.me.id ? "admin" : "member");
    } catch { state.members = []; state.myRole = g.ownerId === state.me.id ? "admin" : "member"; }

    // Refresh unread / read-state for this guild's channels (merge, don't reset —
    // other guilds' state must survive for the server-rail indicators).
    await mergeReadState(g.id);

    // Ensure we're subscribed to this guild (loadGuilds already did all of them).
    ws.subscribeGuild(g.id);

    renderGuildRail();
    renderChannels();

    // Auto-open the first text channel, else show empty.
    const firstText = (g.channels || []).filter((c) => c.type === "text").sort((a, b) => a.position - b.position)[0];
    if (firstText) selectChannel(firstText.id);
    else showView("empty");
  }

  async function selectChannel(id) {
    const g = state.currentGuild;
    const c = g && (g.channels || []).find((x) => x.id === id);
    if (!c) return;

    // NOTE: navigating between channels never disconnects an active voice call.
    // You stay connected until you click Leave (or join a different voice channel).
    state.currentChannel = c;
    renderChannels();
    // mobile: reveal content over the sidebar
    $("content").classList.remove("hide-mobile");

    if (c.type === "voice" && !voiceChatOpen[c.id]) {
      ws.unsubscribe();
      clearTyping();
      showView("voice");
      if (voice.room && voice.channelId === c.id) {
        // already in this call — show the live call screen
        renderVoiceConnected();
        voice.rebuildRoster();
        voice.syncControls();
      } else if (voice.available()) {
        // clicking a voice channel joins it directly (Discord-style) — no
        // separate "Join Voice" step. join() leaves any current call first.
        renderVoiceConnecting(c);
        voice.join(c);
      } else {
        renderVoiceIdle(c);
      }
      return;
    }

    // text channel, OR a voice channel's text chat (text-in-voice) — never leaves a call
    showView("chat");
    renderChannelHeader(c);
    updateSlowModeUI(c);
    cancelReply();
    clearTyping();
    closeMention();
    ws.subscribe(c.id);
    renderMembers();
    await loadMessages(c.id);
  }

  // Chat header: channel glyph + name, plus the topic (click to expand). For a
  // voice channel's text chat, a "Show Call" toggle returns to the call view.
  function renderChannelHeader(c) {
    const el = $("chat-channel-name");
    el.innerHTML = "";
    const glyph = c.type === "voice" ? "volume" : (c.adminOnly ? "lock" : "hash");
    el.append(h("span", { class: "glyph" }, icon(glyph, 20)), h("span", { class: "ch-name", text: c.name }));
    if (c.type === "voice") {
      el.append(h("button", { class: "ch-toggle", "aria-label": "Show call",
        onclick: () => { voiceChatOpen[c.id] = false; selectChannel(c.id); } }, icon("volume", 15), h("span", { text: "Call" })));
    }
    if (c.topic) {
      el.append(h("span", { class: "ch-topic-sep" }),
        h("span", { class: "ch-topic", text: c.topic, "aria-label": "Click to view the full topic",
          onclick: () => openTopicModal(c) }));
    }
  }
  function openTopicModal(c) {
    modal({
      title: "#" + c.name,
      body: [h("div", { class: "topic-full", text: c.topic || "No topic set." })],
      footer: [h("button", { class: "btn", text: "Close", onclick: closeModal })],
    });
  }

  async function loadMessages(channelId) {
    state.messages = [];
    state.hasMoreOlder = false;
    state.newDivider = null;
    $("message-list").innerHTML = "";
    let list;
    try { list = await API.messages(channelId); } catch (e) { toast(e.message, true); return; }
    if (state.currentChannel && state.currentChannel.id !== channelId) return; // switched away
    // API returns newest-first; store ascending for display.
    state.messages = list.slice().reverse();
    state.hasMoreOlder = list.length >= 50;
    // capture the unread boundary for the "New messages" divider before acking.
    // If the channel is unread but we have no read boundary (never opened, e.g. a
    // fresh mention), anchor the divider at the very top so all messages read as new.
    const rs = state.readState[channelId];
    if (rs && rs.lastReadId) state.newDivider = rs.lastReadId;
    else if (isUnread(channelId)) state.newDivider = "0";
    else state.newDivider = null;
    renderMessages();
    // jump to bottom on first load
    const scroll = $("message-scroll");
    scroll.scrollTop = scroll.scrollHeight;
    // opening the channel marks it read up to its newest message
    if (state.messages.length) ackChannel(channelId, state.messages[state.messages.length - 1].id);
  }

  async function loadOlderMessages() {
    if (state.loadingOlder || !state.hasMoreOlder || state.messages.length === 0) return;
    state.loadingOlder = true;
    const channelId = state.currentChannel.id;
    const oldestId = state.messages[0].id;
    const scroll = $("message-scroll");
    const prevHeight = scroll.scrollHeight;
    try {
      const older = await API.messages(channelId, oldestId);
      if (state.currentChannel.id !== channelId) return;
      if (older.length < 50) state.hasMoreOlder = false;
      // prepend (older is newest-first -> reverse to ascending)
      state.messages = older.slice().reverse().concat(state.messages);
      renderMessages();
      // preserve scroll position after prepending
      scroll.scrollTop = scroll.scrollHeight - prevHeight;
    } catch (e) { toast(e.message, true); }
    finally { state.loadingOlder = false; }
  }

  // realtime message helpers
  function onIncomingMessage(m) {
    const scroll = $("message-scroll");
    const wasNearBottom = scroll.scrollHeight - scroll.scrollTop - scroll.clientHeight < 160;
    const idx = state.messages.findIndex((x) => x.id === m.id);
    if (idx >= 0) { state.messages[idx] = m; }
    else { state.messages.push(m); }
    // author no longer "typing"
    if (state.typing.has(m.authorId)) {
      clearTimeout(state.typing.get(m.authorId).timeout);
      state.typing.delete(m.authorId);
      renderTyping();
    }
    renderMessages();
    // if we're viewing the bottom, keep the channel marked read
    const mine = state.me && m.authorId === state.me.id;
    if ((wasNearBottom || mine) && state.currentChannel && state.messages.length) {
      ackChannel(state.currentChannel.id, state.messages[state.messages.length - 1].id);
    }
  }
  function upsertMessage(m) {
    const idx = state.messages.findIndex((x) => x.id === m.id);
    if (idx >= 0) state.messages[idx] = m; else state.messages.push(m);
    renderMessages();
  }
  function removeMessage(id) {
    const idx = state.messages.findIndex((x) => x.id === id);
    if (idx >= 0) { state.messages.splice(idx, 1); renderMessages(); }
  }

  // ---- composer / sending ----
  let pendingFiles = [];       // File[] queued for upload
  let lastTypingSent = 0;
  const slowUntil = {};        // channelId -> ms timestamp you can next post (slow mode)
  const voiceChatOpen = {};    // voice channelId -> true when viewing its text chat instead of the call

  function addPendingFiles(fileList) {
    for (const f of fileList) pendingFiles.push(f);
    renderPending();
  }
  function renderPending() {
    const wrap = $("pending-attachments");
    wrap.innerHTML = "";
    pendingFiles.forEach((f, i) => {
      const chip = h("div", { class: "pending-chip" });
      if (f.type.startsWith("image/")) {
        const url = URL.createObjectURL(f);
        chip.appendChild(h("img", { src: url, alt: f.name }));
      } else {
        chip.appendChild(h("span", { class: "file-icon" }, icon("file", 20)));
      }
      chip.append(
        h("span", { class: "pname", text: f.name }),
        h("button", { class: "remove", "aria-label": "Remove",
          onclick: () => { pendingFiles.splice(i, 1); renderPending(); } }, icon("x", 16))
      );
      wrap.appendChild(chip);
    });
  }

  async function sendMessage() {
    const input = $("composer-input");
    const raw = input.value.trim();
    if (!raw && pendingFiles.length === 0) return;
    // text channels + voice-channel text chat (text-in-voice) can both receive messages
    if (!state.currentChannel) return;

    // client-side slow-mode gate (server enforces too)
    const cch = state.currentChannel;
    if ((cch.slowModeSeconds || 0) > 0 && !amAdmin()) {
      const remain = Math.ceil(((slowUntil[cch.id] || 0) - Date.now()) / 1000);
      if (remain > 0) { toast("Slow mode — wait " + remain + "s"); return; }
    }

    // Convert @DisplayName drafts → <@userId>, and :shortcode: → emoji.
    const content = convertEmojiShortcodes(convertMentions(raw));

    const channelId = state.currentChannel.id;
    const files = pendingFiles;
    pendingFiles = [];
    renderPending();
    const replyToId = state.replyingTo ? state.replyingTo.id : null;
    cancelReply();
    closeMention();
    state.draftMentions = {};
    input.value = "";
    autoGrow(input);

    // 1) upload attachments (two-step), collect ids
    let attachmentIds = [];
    if (files.length) {
      try {
        const uploaded = await Promise.all(files.map((f) => API.upload(f)));
        attachmentIds = uploaded.map((u) => u.id);
      } catch (e) { toast("Upload failed: " + e.message, true); return; }
    }

    // 2) post the message
    try {
      const msg = await API.send(channelId, content, attachmentIds, replyToId);
      // start the slow-mode cooldown for this channel
      const slow = cch.slowModeSeconds || 0;
      if (slow > 0 && !amAdmin()) { slowUntil[channelId] = Date.now() + slow * 1000; updateSlowModeUI(cch); }
      // The WS 'message' echo will render it; but render immediately in case
      // we aren't subscribed yet (avoids a perceived lag).
      if (state.currentChannel && state.currentChannel.id === channelId) onIncomingMessage(msg);
    } catch (e) {
      if (e.status === 429) { // server slow-mode rejection — start a cooldown from the message
        const m = /(\d+)s/.exec(e.message); const secs = m ? parseInt(m[1], 10) : (cch.slowModeSeconds || 0);
        slowUntil[channelId] = Date.now() + secs * 1000; updateSlowModeUI(cch);
      }
      input.value = content; autoGrow(input); // restore the draft so it isn't lost
      toast(e.message, true);
    }
  }

  // Composer slow-mode indicator + countdown.
  let _slowTimer = null;
  function updateSlowModeUI(c) {
    const input = $("composer-input");
    if (!input) return;
    clearInterval(_slowTimer); _slowTimer = null;
    const slow = c && (c.slowModeSeconds || 0);
    const tick = () => {
      if (!state.currentChannel || state.currentChannel.id !== c.id) { clearInterval(_slowTimer); return; }
      const remain = Math.ceil(((slowUntil[c.id] || 0) - Date.now()) / 1000);
      if (slow > 0 && !amAdmin() && remain > 0) {
        input.placeholder = "Slow mode — wait " + remain + "s";
        input.classList.add("slowed");
      } else {
        input.placeholder = "Message" + (c.type === "voice" ? " " + c.name : c.name ? " #" + c.name : "") + "…";
        input.classList.remove("slowed");
        if (!(slow > 0 && !amAdmin())) clearInterval(_slowTimer);
      }
    };
    tick();
    if (slow > 0 && !amAdmin()) _slowTimer = setInterval(tick, 1000);
  }

  function maybeSendTyping() {
    if (!state.currentChannel || state.currentChannel.type !== "text") return;
    const now = Date.now();
    if (now - lastTypingSent > 2500) {
      lastTypingSent = now;
      ws.typing(state.currentChannel.id);
    }
  }

  // ---- message save / delete ----
  async function toggleSave(m) {
    try {
      const updated = m.saved ? await API.unsaveMsg(m.id) : await API.saveMsg(m.id);
      upsertMessage(updated);
    } catch (e) { toast(e.message, true); }
  }
  async function deleteMessage(m) {
    if (!confirm("Delete this message?")) return;
    try { await API.deleteMsg(m.id); removeMessage(m.id); }
    catch (e) { toast(e.message, true); }
  }

  // ---- channels (admin) ----
  async function deleteChannel(c) {
    if (!confirm(`Delete #${c.name}? This cannot be undone.`)) return;
    try {
      await API.deleteChannel(c.id);
      // remove locally
      state.currentGuild.channels = state.currentGuild.channels.filter((x) => x.id !== c.id);
      if (state.currentChannel && state.currentChannel.id === c.id) {
        state.currentChannel = null;
        showView("empty");
      }
      renderChannels();
      toast("Channel deleted");
    } catch (e) { toast(e.message, true); }
  }

  // ---- members (admin) ----
  async function addMember() {
    const input = $("member-add-input");
    const username = input.value.trim();
    if (!username) return;
    try {
      await API.addMember(state.currentGuild.id, username);
      input.value = "";
      state.members = await API.members(state.currentGuild.id);
      renderMembers();
      toast(`Added @${username}`);
    } catch (e) { toast(e.message, true); }
  }
  async function changeRole(m, role) {
    try {
      await API.setRole(state.currentGuild.id, m.userId, role);
      m.role = role;
      // if I demoted myself... (can't, self excluded) — refresh my role anyway
      renderMembers();
      renderChannels();
    } catch (e) { toast(e.message, true); }
  }
  async function kickMember(m) {
    if (!confirm(`Kick ${m.displayName || m.username}?`)) return;
    try {
      await API.kick(state.currentGuild.id, m.userId);
      state.members = state.members.filter((x) => x.userId !== m.userId);
      renderMembers();
      toast("Member removed");
    } catch (e) { toast(e.message, true); }
  }

  // ---- auth actions ----
  async function submitAuth(e) {
    e.preventDefault();
    const username = $("auth-username").value.trim();
    const password = $("auth-password").value;
    const displayName = $("auth-displayname").value.trim();
    const errEl = $("auth-error");
    errEl.textContent = "";
    const btn = $("auth-submit");
    btn.disabled = true;
    try {
      const res = state.authMode === "register"
        ? await API.register({ username, password, displayName: displayName || undefined })
        : await API.login({ username, password });
      state.token = res.token;
      localStorage.setItem("ephemeral_token", res.token);
      state.me = res.user;
      ensureMyPresence();
      showApp();
      renderUserBar();
      ws.connect();
      await loadGuilds();
    } catch (err) {
      errEl.textContent = err.message;
    } finally {
      btn.disabled = false;
    }
  }

  function handleLogout(silent) {
    voice.leave();
    ws.close();
    closePopover();
    closeModal();
    localStorage.removeItem("ephemeral_token");
    Object.assign(state, {
      token: null, me: null, guilds: [], currentGuild: null,
      currentChannel: null, messages: [], members: [], myRole: "member",
      presence: {}, replyingTo: null, editingId: null,
      readState: {}, newDivider: null, draftMentions: {},
      dmMode: false, dms: [], currentDm: null,
    });
    renderReplyBar();
    clearTyping();
    showAuth();
    if (!silent) { $("auth-password").value = ""; }
  }

  /* =======================================================================
   * MODALS
   * ===================================================================== */

  function closeModal() { $("modal-root").innerHTML = ""; }

  function modal({ title, subtitle, body, footer }) {
    const backdrop = h("div", { class: "modal-backdrop",
      onclick: (e) => { if (e.target === backdrop) closeModal(); } });
    const m = h("div", { class: "modal" },
      h("div", { class: "modal-header" },
        h("h3", { text: title }),
        subtitle ? h("p", { text: subtitle }) : null),
      h("div", { class: "modal-body" }, ...body),
      h("div", { class: "modal-footer" }, ...footer)
    );
    backdrop.appendChild(m);
    $("modal-root").innerHTML = "";
    $("modal-root").appendChild(backdrop);
    // Esc closes; autofocus first input.
    const firstInput = m.querySelector("input");
    if (firstInput) setTimeout(() => firstInput.focus(), 0);
    return { backdrop, root: m };
  }

  function openCreateGuildModal() {
    const input = h("input", { class: "text-input", placeholder: "My server", maxlength: "80" });
    const err = h("div", { class: "modal-error" });
    const submit = async () => {
      const name = input.value.trim();
      if (!name) { err.textContent = "Enter a name"; return; }
      try {
        const g = await API.createGuild(name);
        state.guilds.push(g);
        renderGuildRail();
        closeModal();
        selectGuild(g.id);
        toast("Server created");
      } catch (e) { err.textContent = e.message; }
    };
    input.addEventListener("keydown", (e) => { if (e.key === "Enter") submit(); });
    modal({
      title: "Create a server",
      subtitle: "Your server is where you and your people hang out.",
      body: [h("label", {}, "Server name", input), err],
      footer: [
        h("button", { class: "btn btn-secondary", text: "Cancel", onclick: closeModal }),
        h("button", { class: "btn", text: "Create", onclick: submit }),
      ],
    });
  }

  function openCreateChannelModal(defaultType) {
    let type = defaultType || "text";
    const input = h("input", { class: "text-input", placeholder: "new-channel", maxlength: "80" });
    const err = h("div", { class: "modal-error" });
    const textBtn = h("button", { class: type === "text" ? "active" : "" }, icon("hash", 16), h("span", { text: "Text" }));
    const voiceBtn = h("button", { class: type === "voice" ? "active" : "" }, icon("volume", 16), h("span", { text: "Voice" }));
    textBtn.onclick = () => { type = "text"; textBtn.classList.add("active"); voiceBtn.classList.remove("active"); };
    voiceBtn.onclick = () => { type = "voice"; voiceBtn.classList.add("active"); textBtn.classList.remove("active"); };
    const adminCb = h("input", { type: "checkbox" });
    const adminRow = h("div", { class: "set-row set-toggle" },
      h("div", { class: "set-label" }, h("div", {}, icon("lock", 14), " Admin-only channel"),
        h("div", { class: "set-sub", text: "Only admins can see and use this channel." })),
      h("label", { class: "switch" }, adminCb, h("span", { class: "slider" })));
    const submit = async () => {
      const name = input.value.trim();
      if (!name) { err.textContent = "Enter a channel name"; return; }
      try {
        const c = await API.createChannel(state.currentGuild.id, name, type, adminCb.checked);
        state.currentGuild.channels.push(c);
        renderChannels();
        closeModal();
        selectChannel(c.id);
        toast("Channel created");
      } catch (e) { err.textContent = e.message; }
    };
    input.addEventListener("keydown", (e) => { if (e.key === "Enter") submit(); });
    modal({
      title: "Create channel",
      body: [
        h("label", {}, "Channel type", h("div", { class: "seg" }, textBtn, voiceBtn)),
        h("label", {}, "Channel name", input),
        adminRow,
        err,
      ],
      footer: [
        h("button", { class: "btn btn-secondary", text: "Cancel", onclick: closeModal }),
        h("button", { class: "btn", text: "Create", onclick: submit }),
      ],
    });
  }

  // Small reusable "rename" modal (Edit Channel / Edit Server).
  function openRenameModal({ title, label, current, placeholder, onSubmit }) {
    const input = h("input", { class: "text-input", placeholder: placeholder || "", maxlength: "100" });
    input.value = current || "";
    const err = h("div", { class: "modal-error" });
    const submit = async () => {
      const name = input.value.trim();
      if (!name) { err.textContent = "Enter a name"; return; }
      if (name === current) { closeModal(); return; }
      try { await onSubmit(name); closeModal(); }
      catch (e) { err.textContent = e.message; }
    };
    input.addEventListener("keydown", (e) => { if (e.key === "Enter") submit(); });
    modal({
      title,
      body: [h("label", {}, label, input), err],
      footer: [
        h("button", { class: "btn btn-secondary", text: "Cancel", onclick: closeModal }),
        h("button", { class: "btn", text: "Save", onclick: submit }),
      ],
    });
    setTimeout(() => { input.focus(); input.select(); }, 0);
  }

  // Generic confirm dialog. `danger` styles the confirm button red.
  function openConfirmModal({ title, subtitle, confirmLabel, danger, onConfirm }) {
    const err = h("div", { class: "modal-error" });
    const go = async () => {
      try { await onConfirm(); closeModal(); }
      catch (e) { err.textContent = e.message; }
    };
    modal({
      title, subtitle,
      body: [err],
      footer: [
        h("button", { class: "btn btn-secondary", text: "Cancel", onclick: closeModal }),
        h("button", { class: "btn" + (danger ? " btn-danger" : ""), text: confirmLabel || "Confirm", onclick: go }),
      ],
    });
  }

  /* ---- client-side notification mute (per channel / per server) ---- */
  const MUTE_KEY = "ephemeral_muted";
  let muted = (() => { try { return JSON.parse(localStorage.getItem(MUTE_KEY)) || {}; } catch { return {}; } })();
  function isMuted(id) { return !!muted[id]; }
  function toggleMute(id) {
    if (muted[id]) delete muted[id]; else muted[id] = true;
    try { localStorage.setItem(MUTE_KEY, JSON.stringify(muted)); } catch {}
    renderChannels();
    renderGuildRail();
    pushSettings();
  }

  // Mark every text channel in a guild read.
  function markGuildRead(g) {
    for (const c of (g.channels || [])) {
      if (c.type !== "text") continue;
      const rs = state.readState[c.id];
      if (rs && rs.latestId) ackChannel(c.id, rs.latestId);
    }
    toast("Marked as read");
  }

  // ---- server (guild) actions ----
  function renameGuildFlow(g) {
    openRenameModal({
      title: "Edit server", label: "Server name", current: g.name, placeholder: "My server",
      onSubmit: async (name) => {
        const updated = await API.renameGuild(g.id, name);
        applyGuildUpdate(updated);
        toast("Server renamed");
      },
    });
  }
  // Slow-mode presets (label -> seconds), matching Discord.
  const SLOWMODES = [["Off", 0], ["5s", 5], ["10s", 10], ["15s", 15], ["30s", 30], ["1m", 60],
    ["2m", 120], ["5m", 300], ["10m", 600], ["15m", 900], ["30m", 1800], ["1h", 3600], ["2h", 7200], ["6h", 21600]];

  function renameChannelFlow(c) {
    const isText = c.type === "text";
    const nameInput = h("input", { class: "text-input", maxlength: "100" });
    nameInput.value = c.name;
    const topicInput = h("textarea", { class: "text-input", rows: "2", maxlength: "1024",
      placeholder: "Let people know what this channel is about" });
    topicInput.value = c.topic || "";
    const slowSel = h("select", { class: "text-input" });
    for (const [lbl, sec] of SLOWMODES) slowSel.appendChild(h("option", { value: String(sec), text: lbl }));
    slowSel.value = String(c.slowModeSeconds || 0);
    const limitInput = h("input", { class: "text-input", type: "number", min: "0", max: "99", value: String(c.userLimit || 0) });
    const err = h("div", { class: "modal-error" });

    const patch = (updated) => {
      const g = state.currentGuild;
      if (g) { const i = (g.channels || []).findIndex((x) => x.id === c.id); if (i >= 0) g.channels[i] = updated; }
      if (state.currentChannel && state.currentChannel.id === c.id) { state.currentChannel = updated; renderChannelHeader(updated); }
      renderChannels();
    };
    const submit = async () => {
      const body = { name: nameInput.value.trim() };
      if (isText) { body.topic = topicInput.value; body.slowModeSeconds = parseInt(slowSel.value, 10) || 0; }
      else { body.userLimit = Math.max(0, Math.min(99, parseInt(limitInput.value, 10) || 0)); }
      try { patch(await API.updateChannel(c.id, body)); closeModal(); toast("Channel updated"); }
      catch (e) { err.textContent = e.message; }
    };

    const body = [h("label", {}, "Channel name", nameInput)];
    if (isText) {
      body.push(h("label", {}, "Channel topic", topicInput));
      body.push(h("label", {}, "Slow mode", slowSel));
      body.push(h("div", { class: "set-note", text: "Members must wait between messages. Admins are exempt." }));
    } else {
      body.push(h("label", {}, "User limit", limitInput));
      body.push(h("div", { class: "set-note", text: "0 = unlimited. Admins can always join." }));
    }
    body.push(err);
    nameInput.addEventListener("keydown", (e) => { if (e.key === "Enter") submit(); });
    modal({
      title: "Edit channel",
      body,
      footer: [
        h("button", { class: "btn btn-secondary", text: "Cancel", onclick: closeModal }),
        h("button", { class: "btn", text: "Save", onclick: submit }),
      ],
    });
    setTimeout(() => { nameInput.focus(); nameInput.select(); }, 0);
  }
  async function toggleChannelAdminOnly(c) {
    try {
      const updated = await API.setChannelAdminOnly(c.id, !c.adminOnly);
      const g = state.currentGuild;
      if (g) { const i = (g.channels || []).findIndex((x) => x.id === c.id); if (i >= 0) g.channels[i] = updated; }
      if (state.currentChannel && state.currentChannel.id === c.id) state.currentChannel = updated;
      renderChannels();
      toast(updated.adminOnly ? "Channel is now admin-only" : "Channel is now public");
    } catch (e) { toast(e.message, true); }
  }

  function leaveGuildFlow(g) {
    openConfirmModal({
      title: "Leave '" + g.name + "'?",
      subtitle: "You won't be able to rejoin unless the server is public or you're re-invited.",
      confirmLabel: "Leave Server", danger: true,
      onConfirm: async () => {
        await API.leaveGuild(g.id);
        removeGuildLocally(g.id);
        toast("Left server");
      },
    });
  }
  function deleteGuildFlow(g) {
    openConfirmModal({
      title: "Delete '" + g.name + "'?",
      subtitle: "This permanently deletes the server, its channels and all messages. This cannot be undone.",
      confirmLabel: "Delete Server", danger: true,
      onConfirm: async () => {
        await API.deleteGuild(g.id);
        removeGuildLocally(g.id);
        toast("Server deleted");
      },
    });
  }
  function applyGuildUpdate(updated) {
    const i = state.guilds.findIndex((x) => x.id === updated.id);
    if (i >= 0) state.guilds[i] = { ...state.guilds[i], ...updated };
    if (state.currentGuild && state.currentGuild.id === updated.id) {
      state.currentGuild = { ...state.currentGuild, ...updated };
    }
    renderGuildRail();
    renderChannels();
  }
  function removeGuildLocally(gid) {
    state.guilds = state.guilds.filter((x) => x.id !== gid);
    if (state.currentGuild && state.currentGuild.id === gid) {
      state.currentGuild = null;
      state.currentChannel = null;
      if (ws.guildSub === gid) ws.guildSub = null;
      renderGuildRail();
      const next = state.guilds[0];
      if (next) selectGuild(next.id);
      else { renderChannels(); showView("empty"); }
    } else {
      renderGuildRail();
    }
  }

  async function openDiscoverModal() {
    const listEl = h("div", { style: "display:flex;flex-direction:column;gap:8px;" },
      h("div", { class: "modal-error", style: "color:var(--text-faint)", text: "Loading servers…" }));
    modal({
      title: "Browse servers",
      subtitle: "Join any server on this instance.",
      body: [listEl],
      footer: [h("button", { class: "btn btn-secondary", text: "Close", onclick: closeModal })],
    });
    let all;
    try { all = await API.allGuilds(); } catch (e) { listEl.innerHTML = ""; listEl.appendChild(h("div", { class: "modal-error", text: e.message })); return; }
    const mine = new Set(state.guilds.map((g) => g.id));
    const joinable = all.filter((g) => !mine.has(g.id));
    listEl.innerHTML = "";
    if (joinable.length === 0) {
      listEl.appendChild(h("div", { class: "discover-empty", text: "You've joined every server on this instance." }));
      return;
    }
    for (const g of joinable) {
      const btn = h("button", { class: "btn", text: "Join" });
      const item = h("div", { class: "discover-item" },
        avatar(g.name, g.id, "lg"),
        h("div", { class: "di-info" },
          h("div", { class: "di-name", text: g.name }),
          h("div", { class: "di-sub", text: `${(g.channels || []).length} channel(s)` })
        ),
        btn
      );
      btn.onclick = async () => {
        btn.disabled = true; btn.textContent = "Joining…";
        try {
          const joined = await API.joinGuild(g.id);
          state.guilds.push(joined);
          renderGuildRail();
          item.remove();
          toast(`Joined ${joined.name}`);
          selectGuild(joined.id);
        } catch (e) { toast(e.message, true); btn.disabled = false; btn.textContent = "Join"; }
      };
      listEl.appendChild(item);
    }
  }

  /* =======================================================================
   * POPOVERS — profile card + status menu (anchored, dismissible)
   * ===================================================================== */

  let popoverCleanup = null;
  function closePopover() {
    $("popover-root").innerHTML = "";
    if (popoverCleanup) { popoverCleanup(); popoverCleanup = null; }
    mention = null;
  }

  // Mount `card` in a full-screen layer anchored near `anchorEl`.
  function popover(anchorEl, card) {
    const root = $("popover-root");
    closePopover();
    const layer = h("div", { class: "popover-layer",
      onclick: (e) => { if (e.target === layer) closePopover(); } });
    layer.appendChild(card);
    root.appendChild(layer);
    positionCard(card, anchorEl);
  }

  // Register a scroll-dismiss handler tied to the currently-open popover.
  // Ignores scrolls that happen *inside* the popover itself (e.g. emoji grid).
  function armScrollDismiss() {
    const onScroll = (e) => {
      const root = $("popover-root");
      if (e.target && e.target.nodeType === 1 && root.contains(e.target)) return;
      closePopover();
    };
    window.addEventListener("scroll", onScroll, true);
    const prev = popoverCleanup;
    popoverCleanup = () => { window.removeEventListener("scroll", onScroll, true); if (prev) prev(); };
  }

  // A rect-like anchor at a fixed viewport point (for coordinate-anchored menus).
  function pointAnchor(x, y) {
    return { getBoundingClientRect: () => ({ left: x, right: x, top: y, bottom: y, width: 0, height: 0, x, y }) };
  }

  function positionCard(card, anchorEl) {
    card.style.position = "fixed";
    card.style.visibility = "hidden";
    const place = () => {
      const cw = card.offsetWidth, ch = card.offsetHeight;
      const pad = 8;
      let left, top;
      if (anchorEl && anchorEl.getBoundingClientRect) {
        const r = anchorEl.getBoundingClientRect();
        left = r.right + 10;
        if (left + cw > window.innerWidth - pad) left = r.left - cw - 10;   // flip left
        if (left < pad) left = Math.max(pad, window.innerWidth - cw - pad);
        top = r.top;
        if (top + ch > window.innerHeight - pad) top = window.innerHeight - ch - pad;
        if (top < pad) top = pad;
      } else {
        left = (window.innerWidth - cw) / 2;
        top = (window.innerHeight - ch) / 2;
      }
      card.style.left = Math.round(left) + "px";
      card.style.top = Math.round(top) + "px";
      card.style.visibility = "visible";
    };
    requestAnimationFrame(place);
  }

  /* =======================================================================
   * CONTEXT MENUS — generic, dismiss on outside-click / Esc / scroll
   * ===================================================================== */

  // items: [{label, icon, danger, onClick} | {separator:true} | null]
  function openContextMenu(x, y, items) {
    const root = $("popover-root");
    closePopover();
    const layer = h("div", { class: "popover-layer",
      oncontextmenu: (e) => { e.preventDefault(); closePopover(); },
      onclick: (e) => { if (e.target === layer) closePopover(); } });
    const menu = h("div", { class: "context-menu" });
    for (const it of items) {
      if (!it) continue;
      if (it.separator) { menu.appendChild(h("div", { class: "ctx-sep" })); continue; }
      if (it.info) { menu.appendChild(h("div", { class: "ctx-info" }, it.icon || null, h("span", { text: it.info }))); continue; }
      if (it.quickReactions) {
        const row = h("div", { class: "ctx-reactions" });
        for (const em of it.quickReactions) {
          row.appendChild(h("button", { class: "ctx-react", "aria-label": em, text: em,
            onclick: () => { closePopover(); it.onPickEmoji(em); } }));
        }
        row.appendChild(h("button", { class: "ctx-react more", "aria-label": "More emoji",
          onclick: () => { closePopover(); it.onMore(); } }, icon("smile", 16)));
        menu.appendChild(row);
        continue;
      }
      if (it.custom) { menu.appendChild(it.custom); continue; }
      menu.appendChild(h("button", {
        class: "ctx-item" + (it.danger ? " danger" : ""),
        onclick: () => { closePopover(); if (it.onClick) it.onClick(); },
      },
        it.icon ? it.icon : h("span", { class: "ctx-ic-blank" }),
        h("span", { class: "ctx-label", text: it.label })
      ));
    }
    layer.appendChild(menu);
    root.appendChild(layer);
    positionMenu(menu, x, y);
    armScrollDismiss();
  }

  function positionMenu(menu, x, y) {
    menu.style.position = "fixed";
    menu.style.visibility = "hidden";
    requestAnimationFrame(() => {
      const w = menu.offsetWidth, hh = menu.offsetHeight, pad = 8;
      let left = x, top = y;
      if (left + w > window.innerWidth - pad) left = window.innerWidth - w - pad;
      if (top + hh > window.innerHeight - pad) top = window.innerHeight - hh - pad;
      if (left < pad) left = pad;
      if (top < pad) top = pad;
      menu.style.left = Math.round(left) + "px";
      menu.style.top = Math.round(top) + "px";
      menu.style.visibility = "visible";
    });
  }

  // Quick-reaction row shown at the top of the message context menu (like Discord).
  const QUICK_REACTIONS = ["👍", "😂", "❤️", "🎉", "😮", "😢"];

  function amOwner(g) { return g && state.me && g.ownerId === state.me.id; }

  // When will this message auto-delete? (7-day vanish, unless kept.)
  const VANISH_MS = 7 * 24 * 3600 * 1000;
  function vanishLabel(m) {
    if (m.saved) return "Saved — won't vanish";
    if (m.pinned) return "Pinned — won't vanish";
    const left = new Date(m.createdAt).getTime() + VANISH_MS - Date.now();
    if (left <= 0) return "Vanishing any moment…";
    const d = Math.floor(left / 86400000), hh = Math.floor((left % 86400000) / 3600000), mm = Math.floor((left % 3600000) / 60000);
    const span = d ? d + "d " + hh + "h" : (hh ? hh + "h " + mm + "m" : mm + "m");
    return "Vanishes in " + span;
  }

  function openMessageMenu(m, x, y) {
    const mine = state.me && m.authorId === state.me.id;
    const canDelete = mine || amAdmin();
    const canPin = mine || amAdmin();
    openContextMenu(x, y, [
      { quickReactions: QUICK_REACTIONS, onPickEmoji: (e) => reactWith(m, e),
        onMore: () => openEmojiPicker(pointAnchor(x, y), (e) => reactWith(m, e)) },
      { label: "Add Reaction", icon: icon("smile", 16), onClick: () => openEmojiPicker(pointAnchor(x, y), (e) => reactWith(m, e)) },
      mine ? { label: "Edit Message", icon: icon("pencil", 16), onClick: () => startEdit(m) } : null,
      { label: "Reply", icon: icon("reply", 16), onClick: () => startReply(m) },
      { label: "Copy Text", icon: icon("copy", 16), onClick: () => copyText(m.content || "") },
      { separator: true },
      { label: "Mark Unread", icon: icon("mail-open", 16), onClick: () => markMessageUnread(m) },
      { label: "Copy Message Link", icon: icon("copy", 16), onClick: () => copyMessageLink(m) },
      { separator: true },
      canPin ? { label: m.pinned ? "Unpin Message" : "Pin Message", icon: icon("pin", 16), onClick: () => togglePin(m) } : null,
      mine ? { label: m.saved ? "Remove Bookmark" : "Save Message", icon: icon("bookmark", 16), onClick: () => toggleSave(m) } : null,
      canDelete ? { separator: true } : null,
      canDelete ? { label: "Delete Message", icon: icon("trash", 16), danger: true, onClick: () => deleteMessage(m) } : null,
      { separator: true },
      { label: "Copy Message ID", icon: icon("copy", 16), onClick: () => copyText(m.id) },
      { separator: true },
      { info: vanishLabel(m), icon: icon("hourglass", 14) },
    ]);
  }

  function openUserMenu(userId, x, y, anchorEl) {
    const g = state.currentGuild;
    const target = state.members.find((mm) => mm.userId === userId);
    const isSelf = state.me && userId === state.me.id;
    const isOwnerTarget = g && g.ownerId === userId;
    const canModerate = amAdmin() && !isSelf && !isOwnerTarget && target;
    openContextMenu(x, y, [
      { label: "View Profile", icon: icon("user", 16), onClick: () => openProfileCard(userId, anchorEl) },
      !isSelf ? { label: "Message", icon: icon("message-circle", 16), onClick: () => openDmWithUser(userId) } : null,
      !isSelf ? { label: "Mention", icon: icon("at-sign", 16), onClick: () => mentionUser(userId) } : null,
      canModerate ? { separator: true } : null,
      canModerate ? { label: target.role === "admin" ? "Remove Admin" : "Make Admin", icon: icon("shield", 16),
        onClick: () => changeRole(target, target.role === "admin" ? "member" : "admin") } : null,
      canModerate ? { label: "Kick " + (target.displayName || target.username), icon: icon("log-out", 16),
        danger: true, onClick: () => kickMember(target) } : null,
      { separator: true },
      { label: "Copy User ID", icon: icon("copy", 16), onClick: () => copyText(userId) },
    ]);
  }

  function openChannelMenu(c, x, y) {
    const isText = c.type === "text";
    const chanMuted = isMuted(c.id);
    openContextMenu(x, y, [
      isText ? { label: "Mark As Read", icon: icon("check", 16), onClick: () => markChannelRead(c) } : null,
      isText ? { separator: true } : null,
      isText ? { label: chanMuted ? "Unmute Channel" : "Mute Channel", icon: icon(chanMuted ? "bell" : "bell-off", 16),
        onClick: () => toggleMute(c.id) } : null,
      isText ? { separator: true } : null,
      amAdmin() ? { label: "Edit Channel", icon: icon("settings", 16), onClick: () => renameChannelFlow(c) } : null,
      amAdmin() ? { label: c.adminOnly ? "Make Public" : "Make Admin-Only", icon: icon(c.adminOnly ? "lock-open" : "lock", 16),
        onClick: () => toggleChannelAdminOnly(c) } : null,
      { label: "Copy Link", icon: icon("copy", 16), onClick: () => copyText(location.origin + "/#c/" + c.id) },
      { label: "Copy Channel ID", icon: icon("copy", 16), onClick: () => copyText(c.id) },
      amAdmin() ? { separator: true } : null,
      amAdmin() ? { label: "Delete Channel", icon: icon("trash", 16), danger: true, onClick: () => deleteChannel(c) } : null,
    ]);
  }

  // Server menu — from a right-click on the rail icon OR clicking the server name.
  function openGuildMenu(g, x, y) {
    if (!g) return;
    const gMuted = isMuted(g.id);
    openContextMenu(x, y, [
      { label: "Mark As Read", icon: icon("check", 16), onClick: () => markGuildRead(g) },
      { separator: true },
      { label: gMuted ? "Unmute Server" : "Mute Server", icon: icon(gMuted ? "bell" : "bell-off", 16),
        onClick: () => toggleMute(g.id) },
      amAdmin() ? { separator: true } : null,
      amAdmin() ? { label: "Edit Server", icon: icon("settings", 16), onClick: () => renameGuildFlow(g) } : null,
      amAdmin() ? { label: "Create Channel", icon: icon("plus", 16), onClick: () => openCreateChannelModal("text") } : null,
      { separator: true },
      { label: "Copy Server ID", icon: icon("copy", 16), onClick: () => copyText(g.id) },
      { separator: true },
      amOwner(g)
        ? { label: "Delete Server", icon: icon("trash", 16), danger: true, onClick: () => deleteGuildFlow(g) }
        : { label: "Leave Server", icon: icon("log-out", 16), danger: true, onClick: () => leaveGuildFlow(g) },
    ]);
  }

  // Right-click on a participant tile while in a voice call.
  function openVoiceTileMenu(userId, x, y) {
    const isSelf = state.me && userId === state.me.id;
    const locallyMuted = voice.isLocallyMuted && voice.isLocallyMuted(userId);
    const items = [
      { label: "View Profile", icon: icon("user", 16), onClick: () => openProfileCard(userId, pointAnchor(x, y)) },
      !isSelf ? { label: "Mention", icon: icon("at-sign", 16), onClick: () => { const c = state.currentGuild && (state.currentGuild.channels || []).find((ch) => ch.type === "text"); if (c && (!state.currentChannel || state.currentChannel.type !== "text")) selectChannel(c.id); mentionUser(userId); } } : null,
      !isSelf ? { separator: true } : null,
      !isSelf ? { label: locallyMuted ? "Unmute (for me)" : "Mute (for me)", icon: icon(locallyMuted ? "volume" : "mic-off", 16),
        onClick: () => voice.toggleLocalMute(userId) } : null,
    ];
    // per-user volume slider (0–200%), local to you
    if (!isSelf && voice.room) {
      const cur = Math.round((voice.volumeOf(userId)) * 100);
      const label = h("div", { class: "ctx-vol-label" }, icon("volume", 14), h("span", { text: "User Volume" }), h("span", { class: "ctx-vol-val", text: cur + "%" }));
      const slider = h("input", { type: "range", class: "range ctx-vol", min: "0", max: "200", step: "1", value: String(cur) });
      slider.addEventListener("input", () => { label.querySelector(".ctx-vol-val").textContent = slider.value + "%"; voice.setVolume(userId, (+slider.value) / 100); });
      slider.addEventListener("click", (e) => e.stopPropagation());
      items.push({ custom: h("div", { class: "ctx-vol-row" }, label, slider) });
    }
    openContextMenu(x, y, items);
  }

  // Mark a message (and everything after it) unread by moving the read boundary
  // back to the message just before it.
  function markMessageUnread(m) {
    const cid = m.channelId || (state.currentChannel && state.currentChannel.id);
    if (!cid) return;
    const idx = state.messages.findIndex((x) => x.id === m.id);
    const boundary = idx > 0 ? state.messages[idx - 1].id : "0";
    const rs = state.readState[cid] || (state.readState[cid] = { channelId: cid, mentionCount: 0, lastReadId: null, latestId: null });
    rs.lastReadId = boundary;
    if (state.messages.length) rs.latestId = state.messages[state.messages.length - 1].id;
    state.newDivider = boundary;
    renderMessages();
    renderChannels();
    // persist the boundary (skip the sentinel, which isn't a real id)
    if (boundary !== "0") { API.ack(cid, boundary).catch(() => {}); }
    toast("Marked unread");
  }

  /* =======================================================================
   * EMOJI PICKER — searchable, category-tabbed grid
   * ===================================================================== */

  const EMOJI = {
    Smileys: [["😀","grinning happy"],["😁","beaming grin"],["😂","joy laugh tears"],["🤣","rofl laugh"],["😊","blush smile"],["😍","heart eyes love"],["😘","kiss"],["😜","wink tongue"],["🤔","thinking"],["😎","cool sunglasses"],["🥳","party celebrate"],["😢","cry sad"],["😭","sob cry"],["😡","angry mad"],["😱","scream shock"],["🥺","pleading puppy"],["😴","sleep tired"],["🤗","hug"],["🤩","star struck"],["😅","sweat smile"],["😇","angel"],["🙄","eye roll"],["😏","smirk"],["🤯","mind blown"],["🥲","tear smile"],["😬","grimace"],["🤠","cowboy"],["🤫","shush quiet"]],
    Gestures: [["👍","thumbs up like yes"],["👎","thumbs down no"],["👌","ok perfect"],["✌️","peace victory"],["🤞","fingers crossed luck"],["🤟","love you"],["🤙","call me"],["👏","clap applause"],["🙌","raised hands praise"],["🙏","pray thanks please"],["👋","wave hi bye"],["🤝","handshake deal"],["💪","muscle strong"],["👊","fist bump"],["✊","raised fist"],["🖐️","hand"],["✋","stop high five"],["🫶","heart hands love"],["👀","eyes look"],["🫡","salute"],["🤌","pinch italian"],["👉","point right"],["👇","point down"],["🤦","facepalm"],["🤷","shrug"]],
    Hearts: [["❤️","red heart love"],["🧡","orange heart"],["💛","yellow heart"],["💚","green heart"],["💙","blue heart"],["💜","purple heart"],["🖤","black heart"],["🤍","white heart"],["🤎","brown heart"],["💔","broken heart"],["❣️","heart exclamation"],["💕","two hearts"],["💞","revolving hearts"],["💓","beating heart"],["💗","growing heart"],["💖","sparkling heart"],["💘","cupid arrow heart"],["💝","heart gift"],["💟","heart decoration"],["♥️","heart suit"]],
    Animals: [["🐶","dog puppy"],["🐱","cat"],["🐭","mouse"],["🐹","hamster"],["🐰","rabbit bunny"],["🦊","fox"],["🐻","bear"],["🐼","panda"],["🐨","koala"],["🐯","tiger"],["🦁","lion"],["🐮","cow"],["🐷","pig"],["🐸","frog"],["🐵","monkey"],["🐔","chicken"],["🐧","penguin"],["🐦","bird"],["🦄","unicorn"],["🐝","bee"],["🦋","butterfly"],["🐢","turtle"],["🐍","snake"],["🐙","octopus"],["🦕","dino"],["🐳","whale"],["🐬","dolphin"],["🦉","owl"]],
    Food: [["🍏","apple green"],["🍎","apple red"],["🍌","banana"],["🍉","watermelon"],["🍇","grapes"],["🍓","strawberry"],["🍑","peach"],["🍒","cherry"],["🍍","pineapple"],["🥑","avocado"],["🍔","burger"],["🍟","fries"],["🍕","pizza"],["🌭","hotdog"],["🌮","taco"],["🍿","popcorn"],["🍩","donut"],["🍪","cookie"],["🎂","cake birthday"],["🍰","cake slice"],["🍫","chocolate"],["🍬","candy"],["☕","coffee"],["🍺","beer"],["🍷","wine"],["🥂","cheers champagne"],["🍜","noodles ramen"],["🍣","sushi"]],
    Activities: [["⚽","soccer football"],["🏀","basketball"],["🏈","football"],["⚾","baseball"],["🎾","tennis"],["🏐","volleyball"],["🎱","pool billiards"],["🏓","ping pong"],["🏸","badminton"],["🥅","goal"],["🏆","trophy win"],["🥇","gold medal"],["🎯","dart target"],["🎮","game controller"],["🕹️","joystick"],["🎲","dice"],["🎸","guitar"],["🎹","piano"],["🎤","mic sing"],["🎧","headphones"],["🎨","art paint"],["🎬","movie film"],["🎉","party tada"],["🎊","confetti"],["🎁","gift present"]],
    Objects: [["💡","idea lightbulb"],["🔥","fire lit hot"],["⭐","star"],["🌟","glowing star"],["✨","sparkles"],["💫","dizzy star"],["💥","boom collision"],["💯","hundred perfect"],["✅","check done"],["❌","cross wrong"],["❓","question"],["❗","exclamation"],["💰","money bag"],["💎","diamond gem"],["🔔","bell"],["📌","pin"],["📎","paperclip"],["✏️","pencil"],["📷","camera"],["💻","laptop"],["📱","phone mobile"],["⏰","alarm clock"],["🔑","key"],["🔒","lock"],["🚀","rocket launch"],["💤","sleep zzz"],["📈","chart up"],["🏠","house home"]],
    Symbols: [["👑","crown"],["🎵","music note"],["💬","speech bubble"],["👁️","eye"],["🩷","pink heart"],["☀️","sun sunny"],["🌙","moon night"],["⚡","lightning bolt"],["🌈","rainbow"],["☁️","cloud"],["❄️","snow cold"],["💧","droplet water"],["🌊","wave ocean"],["🍀","clover luck"],["🌸","blossom flower"],["🌹","rose"],["🌵","cactus"],["🌴","palm tree"],["🎃","pumpkin halloween"],["🎄","christmas tree"],["♻️","recycle"],["⚠️","warning"],["🔴","red circle"],["🟢","green circle"],["🔵","blue circle"]],
  };
  const EMOJI_TAB_ICON = { Smileys: "😀", Gestures: "✋", Hearts: "❤️", Animals: "🐻", Food: "🍔", Activities: "⚽", Objects: "💡", Symbols: "🔣" };

  // onPick(emoji) is called on selection. opts.keepOpen keeps the picker open.
  function openEmojiPicker(anchor, onPick, opts) {
    opts = opts || {};
    const cats = Object.keys(EMOJI);
    let activeCat = cats[0];

    const search = h("input", { class: "text-input emoji-search", placeholder: "Search emoji…" });
    const tabs = h("div", { class: "emoji-tabs" });
    const grid = h("div", { class: "emoji-grid" });
    const card = h("div", { class: "emoji-picker" }, search, tabs, grid);

    const renderGrid = (filter) => {
      grid.innerHTML = "";
      let list = [];
      if (filter) {
        const f = filter.toLowerCase();
        for (const c of cats) for (const [em, kw] of EMOJI[c]) if (kw.indexOf(f) >= 0 || em === filter) list.push(em);
      } else {
        list = EMOJI[activeCat].map((x) => x[0]);
      }
      if (!list.length) { grid.appendChild(h("div", { class: "emoji-empty", text: "No emoji found" })); return; }
      for (const em of list) {
        grid.appendChild(h("button", { class: "emoji-cell", "aria-label": em, text: em,
          onclick: () => { onPick(em); if (!opts.keepOpen) closePopover(); } }));
      }
    };

    for (const c of cats) {
      const t = h("button", { class: "emoji-tab" + (c === activeCat ? " active" : ""), "aria-label": c, text: EMOJI_TAB_ICON[c] || "?",
        onclick: () => {
          activeCat = c; search.value = "";
          [...tabs.children].forEach((x) => x.classList.remove("active"));
          t.classList.add("active");
          renderGrid("");
        } });
      tabs.appendChild(t);
    }
    search.addEventListener("input", () => renderGrid(search.value.trim()));
    search.addEventListener("keydown", (e) => { if (e.key === "Escape") closePopover(); });

    popover(anchor, card);
    armScrollDismiss();
    renderGrid("");
    setTimeout(() => search.focus(), 0);
  }

  /* =======================================================================
   * @MENTION AUTOCOMPLETE (composer)
   * ===================================================================== */

  let mention = null; // { start, end, items, active, el }

  function currentMentionQuery() {
    const input = $("composer-input");
    if (!input) return null;
    const pos = input.selectionStart;
    const before = input.value.slice(0, pos);
    const m = before.match(/(^|\s)@([^\s@]*)$/);
    if (!m) return null;
    const query = m[2];
    return { start: pos - query.length - 1, end: pos, query };
  }

  function updateMentionAutocomplete() {
    const q = currentMentionQuery();
    if (!q || !state.currentChannel || state.currentChannel.type !== "text") { closeMention(); return; }
    const ql = q.query.toLowerCase();
    const members = state.members.filter((m) => {
      const dn = (m.displayName || m.username).toLowerCase();
      return dn.indexOf(ql) >= 0 || m.username.toLowerCase().indexOf(ql) >= 0;
    }).slice(0, 8);
    if (!members.length) { closeMention(); return; }
    mention = { start: q.start, end: q.end, items: members, active: mention ? Math.min(mention.active, members.length - 1) : 0 };
    renderMentionBox();
  }

  function renderMentionBox() {
    const root = $("popover-root");
    let box = root.querySelector(".mention-pop");
    if (!box) { box = h("div", { class: "mention-pop" }); root.appendChild(box); }
    box.innerHTML = "";
    mention.items.forEach((m, i) => {
      const row = h("div", { class: "mention-opt" + (i === mention.active ? " active" : ""),
        onmousedown: (e) => { e.preventDefault(); insertMention(m); },
        onmouseenter: () => { mention.active = i; [...box.children].forEach((c, j) => c.classList.toggle("active", j === i)); } },
        avatar(m.displayName || m.username, m.userId, "sm"),
        h("span", { class: "mo-name", text: m.displayName || m.username }),
        h("span", { class: "mo-user", text: "@" + m.username })
      );
      box.appendChild(row);
    });
    positionMentionBox(box);
    mention.el = box;
  }

  function positionMentionBox(box) {
    const comp = ($("composer-input").closest && $("composer-input").closest(".composer")) || $("composer-input");
    const r = comp.getBoundingClientRect();
    box.style.position = "fixed";
    box.style.left = Math.round(r.left) + "px";
    box.style.width = Math.round(Math.min(r.width, 340)) + "px";
    box.style.bottom = Math.round(window.innerHeight - r.top + 8) + "px";
  }

  function insertMention(m) {
    if (!mention) return;
    const input = $("composer-input");
    const name = m.displayName || m.username;
    const val = input.value;
    const before = val.slice(0, mention.start);
    const after = val.slice(mention.end);
    const insert = "@" + name + " ";
    input.value = before + insert + after;
    const pos = (before + insert).length;
    state.draftMentions[name] = m.userId;
    closeMention();
    input.focus();
    input.setSelectionRange(pos, pos);
    autoGrow(input);
  }

  function closeMention() {
    mention = null;
    const b = $("popover-root").querySelector(".mention-pop:not(.emoji-pop)");
    if (b) b.remove();
  }

  // ---- :emoji: shortcode autocomplete (mirrors @mention) ----
  let emojiAuto = null;
  let _emojiFlat = null;
  function emojiFlat() {
    if (_emojiFlat) return _emojiFlat;
    _emojiFlat = [];
    for (const cat in EMOJI) for (const [em, kw] of EMOJI[cat]) _emojiFlat.push({ em, kw, name: kw.split(" ")[0] });
    return _emojiFlat;
  }
  // :shortcode: -> emoji, auto-applied on send (Discord does this even without the picker)
  let _emojiCodes = null;
  function emojiCodes() {
    if (_emojiCodes) return _emojiCodes;
    _emojiCodes = {};
    for (const e of emojiFlat()) for (const w of e.kw.split(" ")) if (!(w in _emojiCodes)) _emojiCodes[w] = e.em;
    return _emojiCodes;
  }
  function convertEmojiShortcodes(text) {
    const map = emojiCodes();
    return text.replace(/:([a-z0-9_+-]{2,}):/gi, (m, name) => map[name.toLowerCase()] || m);
  }
  function currentEmojiQuery() {
    const input = $("composer-input");
    if (!input) return null;
    const pos = input.selectionStart;
    const before = input.value.slice(0, pos);
    const m = before.match(/(^|\s):([a-z0-9_+-]{2,})$/i); // ':' + 2+ chars, like Discord
    if (!m) return null;
    const query = m[2];
    return { start: pos - query.length - 1, end: pos, query };
  }
  function updateEmojiAutocomplete() {
    const q = currentEmojiQuery();
    if (!q || !state.currentChannel) { closeEmojiAuto(); return; }
    const ql = q.query.toLowerCase();
    const scored = [];
    for (const e of emojiFlat()) {
      let best = -1;
      for (const w of e.kw.split(" ")) { const idx = w.indexOf(ql); if (idx === 0) best = Math.max(best, 2); else if (idx > 0) best = Math.max(best, 1); }
      if (best >= 0) scored.push({ e, best });
    }
    scored.sort((a, b) => b.best - a.best);
    const items = scored.slice(0, 8).map((s) => s.e);
    if (!items.length) { closeEmojiAuto(); return; }
    emojiAuto = { start: q.start, end: q.end, items, active: emojiAuto ? Math.min(emojiAuto.active, items.length - 1) : 0 };
    renderEmojiBox();
  }
  function renderEmojiBox() {
    const root = $("popover-root");
    let box = root.querySelector(".emoji-pop");
    if (!box) { box = h("div", { class: "mention-pop emoji-pop" }); root.appendChild(box); }
    box.innerHTML = "";
    emojiAuto.items.forEach((e, i) => {
      box.appendChild(h("div", { class: "mention-opt" + (i === emojiAuto.active ? " active" : ""),
        onmousedown: (ev) => { ev.preventDefault(); insertEmojiShortcode(e); },
        onmouseenter: () => { emojiAuto.active = i; [...box.children].forEach((c, j) => c.classList.toggle("active", j === i)); } },
        h("span", { class: "emoji-opt-em", text: e.em }),
        h("span", { class: "mo-name", text: ":" + e.name + ":" })));
    });
    positionMentionBox(box);
  }
  function insertEmojiShortcode(e) {
    if (!emojiAuto) return;
    const input = $("composer-input");
    const val = input.value;
    const insert = e.em + " ";
    input.value = val.slice(0, emojiAuto.start) + insert + val.slice(emojiAuto.end);
    const pos = emojiAuto.start + insert.length;
    closeEmojiAuto();
    input.focus(); input.setSelectionRange(pos, pos); autoGrow(input);
  }
  function closeEmojiAuto() {
    emojiAuto = null;
    const b = $("popover-root").querySelector(".emoji-pop");
    if (b) b.remove();
  }

  // Insert a mention from the user context menu.
  function mentionUser(userId) {
    const name = memberName(userId) || "user";
    const input = $("composer-input");
    if (!input) return;
    const val = input.value;
    const pos = input.selectionStart != null ? input.selectionStart : val.length;
    const insert = "@" + name + " ";
    input.value = val.slice(0, pos) + insert + val.slice(pos);
    state.draftMentions[name] = userId;
    const np = pos + insert.length;
    input.focus();
    input.setSelectionRange(np, np);
    autoGrow(input);
  }

  /* =======================================================================
   * UNREAD STATE
   * ===================================================================== */

  // Fetch a guild's read-state and MERGE into the global map (keyed by channelId,
  // which is unique) so every server's unread survives — needed for the rail.
  async function mergeReadState(guildId) {
    try {
      const rsArr = await API.readState(guildId);
      for (const r of rsArr || []) {
        // Baseline never-read channels at their tip so login backlog reads as seen.
        if (r.lastReadId == null && r.latestId != null) r.lastReadId = r.latestId;
        state.readState[r.channelId] = r;
      }
    } catch { /* non-fatal */ }
  }
  async function loadAllReadState() {
    state.readState = {};
    await Promise.all((state.guilds || []).map((g) => mergeReadState(g.id)));
  }

  function isUnread(cid) {
    const rs = state.readState[cid];
    if (!rs) return false;
    if ((rs.mentionCount || 0) > 0) return true; // a mention always surfaces
    if (!rs.latestId) return false;
    if (rs.lastReadId == null) return false; // never-read channels are treated as read
    return rs.latestId > rs.lastReadId;
  }
  function mentionCountFor(cid) {
    const rs = state.readState[cid];
    return rs ? (rs.mentionCount || 0) : 0;
  }

  async function ackChannel(cid, lastReadId) {
    if (!cid || !lastReadId) return;
    const rs = state.readState[cid] || (state.readState[cid] = { channelId: cid, mentionCount: 0, lastReadId: null, latestId: null });
    if (rs.lastReadId === lastReadId && !rs.mentionCount) return; // already up to date
    rs.lastReadId = lastReadId;
    if (!rs.latestId || rs.latestId < lastReadId) rs.latestId = lastReadId;
    rs.mentionCount = 0;
    renderChannels();
    renderGuildRail();
    updateTitleBadge();
    try { await API.ack(cid, lastReadId); } catch (e) { /* non-fatal */ }
  }
  function ackCurrentLatest() {
    if (state.currentChannel && state.currentChannel.type === "text" && state.messages.length) {
      ackChannel(state.currentChannel.id, state.messages[state.messages.length - 1].id);
    }
  }
  function markChannelRead(c) {
    const rs = state.readState[c.id];
    const id = rs && rs.latestId;
    if (id) ackChannel(c.id, id);
    else toast("Nothing new to read");
  }
  // Live: a message arrived in a channel that isn't currently open.
  function markChannelUnreadFromMessage(d) {
    const cid = d.channelId;
    const rs = state.readState[cid] || (state.readState[cid] = { channelId: cid, mentionCount: 0, lastReadId: null, latestId: null });
    rs.latestId = d.id;
    if (d.mentions && state.me && d.mentions.includes(state.me.id)) {
      rs.mentionCount = (rs.mentionCount || 0) + 1;
    }
    renderChannels();
    renderGuildRail();
    maybeNotify(d);
  }

  /* =======================================================================
   * MESSAGE SEARCH — Postgres full-text, with from:/in:/has: filters.
   * ===================================================================== */
  function openSearch(prefill) {
    const g = state.currentGuild;
    const input = h("input", { class: "text-input", placeholder: "Search  —  try  from:name  in:channel  has:image  words",
      autocomplete: "off", spellcheck: "false" });
    if (prefill) input.value = prefill;
    const sortSel = h("select", { class: "text-input search-sort" },
      h("option", { value: "recent", text: "Newest" }),
      h("option", { value: "relevant", text: "Most relevant" }));
    const results = h("div", { class: "search-results" });
    const hint = h("div", { class: "search-hint", text: "Search this server's messages. Filters: from:  in:  has:link|image|file" });
    results.appendChild(hint);

    // resolve from:/in: names to ids against the current guild
    function parse(raw) {
      const params = { sort: sortSel.value, limit: "40" };
      if (g) params.guildId = g.id;
      let text = raw;
      const from = text.match(/(?:^|\s)from:@?([^\s]+)/i);
      if (from) {
        const m = (state.members || []).find((x) => (x.username || "").toLowerCase() === from[1].toLowerCase()
          || (x.displayName || "").toLowerCase() === from[1].toLowerCase());
        if (m) params.authorId = m.userId;
        text = text.replace(from[0], " ");
      }
      const inCh = text.match(/(?:^|\s)in:#?([^\s]+)/i);
      if (inCh && g) {
        const c = (g.channels || []).find((x) => x.name.toLowerCase() === inCh[1].toLowerCase());
        if (c) params.channelId = c.id;
        text = text.replace(inCh[0], " ");
      }
      const has = text.match(/(?:^|\s)has:(link|image|file)/i);
      if (has) { params.has = has[1].toLowerCase(); text = text.replace(has[0], " "); }
      text = text.trim();
      if (text) params.q = text;
      return params;
    }

    let seq = 0;
    async function run() {
      const my = ++seq;
      const raw = input.value.trim();
      const params = parse(raw);
      if (!params.q && !params.authorId && !params.has && !params.channelId) {
        results.innerHTML = ""; results.appendChild(hint); return;
      }
      results.innerHTML = "";
      results.appendChild(h("div", { class: "search-hint", text: "Searching…" }));
      let hits;
      try { hits = await API.search(params); } catch (e) {
        if (my !== seq) return;
        results.innerHTML = ""; results.appendChild(h("div", { class: "search-hint", text: e.message })); return;
      }
      if (my !== seq) return;
      results.innerHTML = "";
      if (!hits.length) { results.appendChild(h("div", { class: "search-hint", text: "No messages found." })); return; }
      results.appendChild(h("div", { class: "search-count", text: hits.length + (hits.length === 40 ? "+ results" : " result" + (hits.length === 1 ? "" : "s")) }));
      for (const hmsg of hits) {
        const card = h("div", { class: "search-hit", onclick: () => { closeModal(); jumpToSearchHit(hmsg); } },
          h("div", { class: "sh-head" },
            avatar(hmsg.authorName, hmsg.authorId, "sm", hmsg.authorId),
            h("span", { class: "sh-author", text: hmsg.authorName }),
            h("span", { class: "sh-chan", text: "#" + (hmsg.channelName || "") }),
            h("span", { class: "sh-time", text: relativeTime(hmsg.createdAt) })),
          h("div", { class: "sh-content", text: hmsg.content || "" }),
          h("button", { class: "sh-jump", text: "Jump", onclick: (e) => { e.stopPropagation(); closeModal(); jumpToSearchHit(hmsg); } })
        );
        results.appendChild(card);
      }
    }

    let t = null;
    input.addEventListener("input", () => { clearTimeout(t); t = setTimeout(run, 300); });
    input.addEventListener("keydown", (e) => { if (e.key === "Enter") { clearTimeout(t); run(); } });
    sortSel.addEventListener("change", run);

    modal({
      title: "Search",
      body: [h("div", { class: "search-bar" }, input, sortSel), results],
      footer: [h("button", { class: "btn btn-secondary", text: "Close", onclick: closeModal })],
    });
    setTimeout(() => input.focus(), 0);
    if (prefill) run();
  }

  // Jump to a search result: switch guild/channel if needed, then flash it.
  async function jumpToSearchHit(hit) {
    if (state.currentGuild && state.currentGuild.id === hit.guildId
        && state.currentChannel && state.currentChannel.id === hit.channelId) {
      jumpToMessage(hit.id);
      return;
    }
    if (!state.currentGuild || state.currentGuild.id !== hit.guildId) {
      await selectGuild(hit.guildId);
    }
    await selectChannel(hit.channelId);
    // give the message list a moment to render, then locate (loads older if needed)
    setTimeout(() => jumpToMessage(hit.id), 400);
  }

  // ---- desktop notifications + ping + title badge ----
  function totalMentions() {
    let n = 0;
    for (const cid in state.readState) n += (state.readState[cid].mentionCount || 0);
    return n;
  }
  function updateTitleBadge() {
    const n = totalMentions();
    document.title = (n > 0 ? "(" + (n > 99 ? "99+" : n) + ") " : "") + "ephemeral";
  }
  let _audioCtx = null;
  function playPing() {
    if (media.notifSound === false) return;
    try {
      _audioCtx = _audioCtx || new (window.AudioContext || window.webkitAudioContext)();
      const c = _audioCtx, t = c.currentTime;
      const o = c.createOscillator(), g = c.createGain();
      o.type = "sine"; o.frequency.setValueAtTime(880, t); o.frequency.setValueAtTime(660, t + 0.09);
      g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(0.12, t + 0.02);
      g.gain.exponentialRampToValueAtTime(0.0001, t + 0.25);
      o.connect(g); g.connect(c.destination); o.start(t); o.stop(t + 0.26);
    } catch {}
  }
  function maybeNotify(d) {
    updateTitleBadge();
    if (!d.mentions || !state.me || !d.mentions.includes(state.me.id)) return;
    const viewing = document.hasFocus() && state.currentChannel && state.currentChannel.id === d.channelId;
    if (viewing) return;
    playPing();
    if (typeof Notification !== "undefined" && Notification.permission === "granted") {
      try {
        const n = new Notification(d.authorName || "New mention", { body: (d.content || "").slice(0, 140), tag: d.id });
        n.onclick = () => { window.focus(); jumpToMessage(d.id); n.close(); };
      } catch {}
    }
  }
  function enableNotifications() {
    if (typeof Notification === "undefined") { toast("Notifications aren't supported here", true); return; }
    Notification.requestPermission().then((p) => {
      toast(p === "granted" ? "Desktop notifications enabled" : "Notifications " + p);
    });
  }

  /* =======================================================================
   * PINNED MESSAGES VIEWER
   * ===================================================================== */

  async function openPinsPopover(anchor) {
    if (!state.currentChannel) return;
    const cid = state.currentChannel.id;
    const listEl = h("div", { class: "pins-list" }, h("div", { class: "pins-empty", text: "Loading…" }));
    const card = h("div", { class: "pins-pop" },
      h("div", { class: "pins-head" }, icon("pin", 15), h("span", { text: "Pinned Messages" })),
      listEl);
    popover(anchor, card);
    armScrollDismiss();
    let pins;
    try { pins = await API.pins(cid); }
    catch (e) { listEl.innerHTML = ""; listEl.appendChild(h("div", { class: "pins-empty", text: e.message })); return; }
    if (!state.currentChannel || state.currentChannel.id !== cid) return;
    listEl.innerHTML = "";
    if (!pins.length) { listEl.appendChild(h("div", { class: "pins-empty", text: "No pinned messages yet." })); positionCard(card, anchor); return; }
    for (const p of pins) {
      listEl.appendChild(h("div", { class: "pin-item", onclick: () => { closePopover(); jumpToMessage(p.id); } },
        avatar(p.authorName || "?", p.authorId, "sm"),
        h("div", { class: "pin-body" },
          h("div", { class: "pin-author", text: p.authorName || "Unknown" }),
          h("div", { class: "pin-snippet", text: replySnippet(p.content) })
        )
      ));
    }
    positionCard(card, anchor);
  }

  /* =======================================================================
   * DEEP LINKS  (#m/<channel>/<message>  and  #c/<channel>)
   * ===================================================================== */

  function handleDeepLink() {
    const hash = location.hash || "";
    let m = hash.match(/^#m\/([^/]+)\/([^/]+)$/);
    if (m) { openDeepLink(m[1], m[2]); return; }
    m = hash.match(/^#c\/([^/]+)$/);
    if (m) { openDeepLink(m[1], null); }
  }
  async function openDeepLink(channelId, messageId) {
    const g = state.guilds.find((gg) => (gg.channels || []).some((c) => c.id === channelId));
    if (!g) return;
    if (!state.currentGuild || state.currentGuild.id !== g.id) await selectGuild(g.id);
    if (!state.currentChannel || state.currentChannel.id !== channelId) await selectChannel(channelId);
    if (messageId) setTimeout(() => jumpToMessage(messageId), 450);
  }

  // ---- jump to present ----
  function jumpToPresent() {
    const s = $("message-scroll");
    s.scrollTop = s.scrollHeight;
    $("jump-present").classList.add("hidden");
    ackCurrentLatest();
  }

  // ---- profile card ----
  async function openProfileCard(userId, anchorEl) {
    if (!userId) return;
    const card = h("div", { class: "profile-card" },
      h("div", { class: "profile-loading", text: "Loading…" }));
    popover(anchorEl, card);
    let u;
    try {
      u = await API.getUser(userId);
    } catch (e) {
      card.innerHTML = "";
      card.appendChild(h("div", { class: "profile-loading", text: e.message }));
      positionCard(card, anchorEl);
      return;
    }
    renderProfileCard(card, u, anchorEl);
  }

  function memberSince(iso) {
    if (!iso) return null;
    const d = new Date(iso);
    if (isNaN(d)) return null;
    return d.toLocaleDateString(undefined, { year: "numeric", month: "long", day: "numeric" });
  }

  function renderProfileCard(card, u, anchorEl) {
    card.innerHTML = "";
    const isMe = state.me && u.id === state.me.id;
    const p = state.presence[u.id];
    const st = presenceState(isMe && !p ? { online: true } : p);
    const dname = u.displayName || u.username;

    const av = avatar(dname, u.id, "xl");             // ring instead of dot on the card
    av.classList.add("profile-avatar", "ring-" + st);

    const info = h("div", { class: "profile-info" },
      h("div", { class: "profile-name", text: dname }),
      h("div", { class: "profile-username", text: "@" + u.username })
    );

    if (u.customStatus) {
      info.appendChild(h("div", { class: "card-sect" },
        h("div", { class: "profile-custom", text: u.customStatus })));
    }

    const details = h("div", { class: "card-sect" });
    if (u.bio) {
      details.appendChild(h("div", { class: "card-label", text: "About" }));
      details.appendChild(h("div", { class: "profile-bio", text: u.bio }));
    }
    // role in the current server, if known
    const member = state.members.find((m) => m.userId === u.id);
    const isOwner = state.currentGuild && state.currentGuild.ownerId === u.id;
    if (member || isOwner) {
      const roleText = isOwner ? "Owner" : (member.role === "admin" ? "Admin" : "Member");
      const roleCls = (member && member.role === "admin") || isOwner ? "" : " member";
      details.appendChild(h("div", { class: "card-label", text: "Role in " + (state.currentGuild ? state.currentGuild.name : "server"), style: "margin-top:10px" }));
      details.appendChild(h("span", { class: "profile-role-badge" + roleCls, text: roleText }));
    }
    const since = memberSince(u.createdAt);
    if (since) {
      details.appendChild(h("div", { class: "profile-since", style: "margin-top:10px" },
        icon("hourglass", 13), h("span", { text: "on ephemeral since " + since })));
    }
    if (details.childNodes.length) info.appendChild(details);

    if (isMe) {
      info.appendChild(h("button", { class: "btn btn-secondary profile-edit-btn",
        onclick: () => renderProfileEdit(card, u, anchorEl) }, icon("pencil", 15), h("span", { text: " Edit profile" })));
    } else {
      info.appendChild(h("button", { class: "btn profile-edit-btn",
        onclick: () => openDmWithUser(userId) }, icon("message-circle", 15), h("span", { text: " Message" })));
    }

    card.append(h("div", { class: "profile-banner" }), av, info);
    positionCard(card, anchorEl);
  }

  function renderProfileEdit(card, u, anchorEl) {
    card.innerHTML = "";
    const dn = h("input", { class: "text-input", maxlength: "60" });
    dn.value = u.displayName || "";
    const cs = h("input", { class: "text-input", maxlength: "80", placeholder: "What's happening?" });
    cs.value = u.customStatus || "";
    const bio = h("textarea", { maxlength: "400", placeholder: "Tell people about yourself" });
    bio.value = u.bio || "";
    const err = h("div", { class: "modal-error" });

    const save = async () => {
      const body = {
        displayName: dn.value.trim() || null,
        customStatus: cs.value.trim() || null,
        bio: bio.value.trim() || null,
      };
      try {
        const updated = await API.updateMe(body);
        const merged = updated || Object.assign({}, u, body);
        // reflect changes across the app
        if (state.me) {
          state.me.displayName = merged.displayName || state.me.displayName;
        }
        const prev = state.presence[u.id] || { online: true, status: "online" };
        state.presence[u.id] = Object.assign({}, prev, { customStatus: merged.customStatus || null });
        applyPresence();
        renderMembers();
        renderMessages();
        renderProfileCard(card, merged, anchorEl);
        toast("Profile updated");
      } catch (e) { err.textContent = e.message; }
    };

    const form = h("div", { class: "profile-form" },
      h("label", {}, "Display name", dn),
      h("label", {}, "Custom status", cs),
      h("label", {}, "About", bio),
      err,
      h("div", { class: "profile-form-actions" },
        h("button", { class: "btn btn-secondary", text: "Cancel", onclick: () => renderProfileCard(card, u, anchorEl) }),
        h("button", { class: "btn", text: "Save", onclick: save })
      )
    );
    card.append(h("div", { class: "profile-info" },
      h("div", { class: "profile-name", text: "Edit profile", style: "margin-bottom:12px" }), form));
    positionCard(card, anchorEl);
  }

  // ---- status menu (user bar) ----
  function openStatusMenu(anchorEl) {
    const me = state.me;
    if (!me) return;
    const cur = state.presence[me.id] || { status: "online" };
    const menu = h("div", { class: "status-menu" });

    const opt = (val, label) => h("div", {
      class: "status-opt" + ((cur.status || "online") === val ? " active" : ""),
      onclick: () => setMyStatus(val),
    }, h("span", { class: "status-dot " + val }), h("span", { text: label }));

    const input = h("input", { class: "text-input status-custom", placeholder: "Set a custom status", maxlength: "80" });
    input.value = cur.customStatus || "";
    input.addEventListener("keydown", (e) => { if (e.key === "Enter") setMyCustomStatus(input.value); });
    input.addEventListener("click", (e) => e.stopPropagation());

    menu.append(
      h("div", { class: "status-menu-head", text: "Set status" }),
      opt("online", "Online"),
      opt("idle", "Idle"),
      opt("dnd", "Do Not Disturb"),
      h("div", { class: "status-menu-sep" }),
      input,
      h("div", { class: "status-menu-sep" }),
      h("button", { class: "status-menu-item", onclick: () => openProfileCard(me.id, anchorEl) },
        icon("user", 16), h("span", { text: " View / edit profile" })),
      h("button", { class: "status-menu-item", onclick: () => { closePopover(); openMediaSettings(); } },
        icon("settings", 16), h("span", { text: " Voice & Video settings" })),
      h("button", { class: "status-menu-item", onclick: () => { closePopover(); enableNotifications(); } },
        icon("bell", 16), h("span", { text: " Enable notifications" })),
      h("button", { class: "status-menu-item", onclick: () => { closePopover(); copyText(me.id); } },
        icon("copy", 16), h("span", { text: " Copy user ID" })),
      h("div", { class: "status-menu-sep" }),
      h("button", { class: "status-menu-item danger", onclick: () => { closePopover(); deleteAccountFlow(); } },
        icon("alert-triangle", 16), h("span", { text: " Delete account" })),
      h("button", { class: "status-menu-item danger", onclick: () => { closePopover(); handleLogout(false); } },
        icon("log-out", 16), h("span", { text: " Log out" }))
    );
    popover(anchorEl, menu);
  }

  // Permanent account deletion — requires typing the username to confirm.
  function deleteAccountFlow() {
    const me = state.me;
    if (!me) return;
    const input = h("input", { class: "text-input", placeholder: me.username });
    const err = h("div", { class: "modal-error" });
    const go = async () => {
      if (input.value.trim() !== me.username) { err.textContent = "Type your username to confirm."; return; }
      try {
        await API.deleteAccount();
        localStorage.removeItem("ephemeral_token");
        location.reload();
      } catch (e) { err.textContent = e.message; }
    };
    modal({
      title: "Delete your account?",
      subtitle: "This permanently erases your account and everything you've ever posted — messages, uploads, reactions, and any servers you own. This cannot be undone.",
      body: [h("label", {}, "Type your username (" + me.username + ") to confirm", input), err],
      footer: [
        h("button", { class: "btn btn-secondary", text: "Cancel", onclick: closeModal }),
        h("button", { class: "btn btn-danger", text: "Delete Everything", onclick: go }),
      ],
    });
  }

  async function setMyStatus(status) {
    const me = state.me;
    if (!me) return;
    const prev = state.presence[me.id] || {};
    state.presence[me.id] = { online: true, status, customStatus: prev.customStatus || null };
    applyPresence();
    closePopover();
    try { await API.updateMe({ status }); } catch (e) { toast(e.message, true); }
  }
  async function setMyCustomStatus(text) {
    const me = state.me;
    if (!me) return;
    const val = text.trim() || null;
    const prev = state.presence[me.id] || { online: true, status: "online" };
    state.presence[me.id] = { online: prev.online !== false, status: prev.status || "online", customStatus: val };
    applyPresence();
    closePopover();
    try { await API.updateMe({ customStatus: val }); toast("Status updated"); } catch (e) { toast(e.message, true); }
  }

  /* =======================================================================
   * 7. EVENT WIRING & BOOTSTRAP
   * ===================================================================== */

  function autoGrow(ta) {
    ta.style.height = "auto";
    ta.style.height = Math.min(ta.scrollHeight, 200) + "px";
  }

  function wire() {
    // auth
    $("auth-form").addEventListener("submit", submitAuth);
    $("auth-toggle-link").addEventListener("click", () => {
      state.authMode = state.authMode === "login" ? "register" : "login";
      renderAuthMode();
    });

    // logout
    $("logout-btn").addEventListener("click", () => handleLogout(false));

    // composer
    const input = $("composer-input");
    input.addEventListener("keydown", (e) => {
      // autocomplete navigation takes priority (emoji, then mention)
      const ac = emojiAuto ? { items: emojiAuto.items, get active() { return emojiAuto.active; }, set active(v) { emojiAuto.active = v; }, render: renderEmojiBox, insert: (it) => insertEmojiShortcode(it), close: closeEmojiAuto }
        : mention ? { items: mention.items, get active() { return mention.active; }, set active(v) { mention.active = v; }, render: renderMentionBox, insert: (it) => insertMention(it), close: closeMention }
        : null;
      if (ac) {
        if (e.key === "ArrowDown") { e.preventDefault(); ac.active = (ac.active + 1) % ac.items.length; ac.render(); return; }
        if (e.key === "ArrowUp") { e.preventDefault(); ac.active = (ac.active - 1 + ac.items.length) % ac.items.length; ac.render(); return; }
        if (e.key === "Enter" || e.key === "Tab") { e.preventDefault(); ac.insert(ac.items[ac.active]); return; }
        if (e.key === "Escape") { e.preventDefault(); ac.close(); return; }
      }
      if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); sendMessage(); }
    });
    input.addEventListener("input", () => { autoGrow(input); maybeSendTyping(); updateMentionAutocomplete(); updateEmojiAutocomplete(); });
    input.addEventListener("click", () => { updateMentionAutocomplete(); updateEmojiAutocomplete(); });
    input.addEventListener("blur", () => setTimeout(() => { closeMention(); closeEmojiAuto(); }, 150));
    $("send-btn").addEventListener("click", sendMessage);

    // message search
    const searchBtn = $("search-btn");
    if (searchBtn) searchBtn.addEventListener("click", () => openSearch());

    // voice message recording
    const micBtn = $("mic-btn");
    if (micBtn) micBtn.addEventListener("click", () => { if (rec) finishVoiceRecording(true); else startVoiceRecording(); });

    // GIF picker (Tenor-backed when the server has a key)
    const gifBtn = $("gif-btn");
    if (gifBtn) gifBtn.addEventListener("click", () => openGifPicker(gifBtn));

    // composer emoji picker — insert the chosen emoji at the caret
    const emojiBtn = $("emoji-btn");
    if (emojiBtn) emojiBtn.addEventListener("click", () => {
      openEmojiPicker(emojiBtn, (em) => {
        const ta = $("composer-input");
        const pos = ta.selectionStart != null ? ta.selectionStart : ta.value.length;
        ta.value = ta.value.slice(0, pos) + em + ta.value.slice(pos);
        const np = pos + em.length;
        ta.focus(); ta.setSelectionRange(np, np); autoGrow(ta);
      }, { keepOpen: true });
    });

    // reveal spoilers on click (delegated)
    $("message-list").addEventListener("click", (e) => {
      const sp = e.target.closest(".spoiler");
      if (sp && !sp.classList.contains("revealed")) { e.stopPropagation(); sp.classList.add("revealed"); }
    });

    // pinned messages viewer
    const pinsBtn = $("pins-btn");
    if (pinsBtn) pinsBtn.addEventListener("click", () => openPinsPopover(pinsBtn));

    // jump to present
    const jp = $("jump-present");
    if (jp) {
      jp.append(h("span", { text: "Jump to present" }), icon("arrow-down", 16));
      jp.addEventListener("click", jumpToPresent);
    }

    // attachments
    $("attach-btn").addEventListener("click", () => $("file-input").click());
    $("file-input").addEventListener("change", (e) => {
      if (e.target.files && e.target.files.length) addPendingFiles(e.target.files);
      e.target.value = ""; // allow re-selecting the same file
    });

    // paste images into composer
    input.addEventListener("paste", (e) => {
      const items = e.clipboardData && e.clipboardData.files;
      if (items && items.length) { addPendingFiles(items); e.preventDefault(); }
    });

    // drag & drop files onto chat
    const chatView = $("chat-view");
    ["dragover", "drop"].forEach((ev) =>
      chatView.addEventListener(ev, (e) => { e.preventDefault(); }));
    chatView.addEventListener("drop", (e) => {
      if (e.dataTransfer && e.dataTransfer.files.length) addPendingFiles(e.dataTransfer.files);
    });

    // infinite scroll (older history) + jump-to-present bar + read tracking
    $("message-scroll").addEventListener("scroll", (e) => {
      const s = e.target;
      if (s.scrollTop < 80) loadOlderMessages();
      const nearBottom = s.scrollHeight - s.scrollTop - s.clientHeight < 120;
      const jpBar = $("jump-present");
      if (jpBar) jpBar.classList.toggle("hidden", nearBottom || !state.currentChannel || state.currentChannel.type !== "text");
      if (nearBottom) ackCurrentLatest();
    });

    // members toggle — records an explicit preference that overrides the
    // width-based auto default until the page reloads
    $("toggle-members-btn").addEventListener("click", () => {
      const col = $("member-column");
      const nowHidden = col.classList.toggle("hidden");
      membersUserPref = !nowHidden;
      $("toggle-members-btn").classList.toggle("active", !nowHidden);
    });
    // re-evaluate members visibility when the window resizes (auto mode hides
    // them on screens too narrow to show them without cramping the chat)
    let _membersResizeT;
    window.addEventListener("resize", () => {
      clearTimeout(_membersResizeT);
      _membersResizeT = setTimeout(applyMembersVisibility, 150);
    });
    $("member-add-btn").addEventListener("click", addMember);
    $("member-add-input").addEventListener("keydown", (e) => { if (e.key === "Enter") addMember(); });

    // voice controls
    $("vc-mic").addEventListener("click", () => voice.toggleMic());
    $("vc-deafen").addEventListener("click", () => voice.toggleDeafen());
    $("vc-cam").addEventListener("click", () => voice.toggleCam());
    $("vc-screen").addEventListener("click", () => voice.toggleScreen());
    $("vc-settings").addEventListener("click", () => openMediaSettings());
    $("user-settings-btn").addEventListener("click", () => openMediaSettings());
    $("vc-leave").addEventListener("click", () => voice.leave());

    // push-to-talk key handling (browser caveat: only fires while this tab is focused)
    const isTypingTarget = (t) => t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.isContentEditable);
    document.addEventListener("keydown", (e) => {
      if (media.inputMode !== "ptt" || !voice.room || e.repeat) return;
      if (isTypingTarget(e.target)) return; // don't hijack typing
      if (e.code === media.pttKey) { e.preventDefault(); voice.pttDown(); }
    });
    document.addEventListener("keyup", (e) => {
      if (media.inputMode !== "ptt" || !voice.room) return;
      if (e.code === media.pttKey) { e.preventDefault(); voice.pttUp(); }
    });


    // mobile back buttons -> reveal sidebar
    const back = () => $("content").classList.add("hide-mobile");
    $("back-btn").addEventListener("click", back);
    $("voice-back-btn").addEventListener("click", back);

    // global esc closes modal + popovers
    document.addEventListener("keydown", (e) => { if (e.key === "Escape") { closeModal(); closePopover(); } });

    // leave the call cleanly if the tab closes
    window.addEventListener("beforeunload", () => { if (voice.room) voice.leave(); });

    // AFK inactivity auto-disconnect: any interaction resets the idle timer; a
    // periodic check leaves the call once you've been idle past media.afkTimeoutMin
    ["mousemove", "mousedown", "keydown", "touchstart", "wheel"].forEach((ev) =>
      window.addEventListener(ev, afkBump, { passive: true }));
    setInterval(afkCheck, 20000);

    // (debug/test hook) report live mic transmit state
    window.__voiceState = () => {
      if (!voice.room) return null;
      const p = voice.room.localParticipant.getTrackPublication(LK.Track.Source.Microphone);
      return { others: voice.room.remoteParticipants.size, micMuted: !p || p.isMuted, intent: voice.mic };
    };

    // deep links (#m/<channel>/<message>, #c/<channel>)
    window.addEventListener("hashchange", handleDeepLink);
  }

  async function boot() {
    renderAuthMode();
    wire();
    if (state.token) {
      await bootstrapSession();
    } else {
      showAuth();
    }
  }

  document.addEventListener("DOMContentLoaded", boot);
})();
