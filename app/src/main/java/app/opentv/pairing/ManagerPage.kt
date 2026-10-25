/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

import app.opentv.R
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The page a phone or laptop sees when managing channels.
 *
 * Like [PairingPage], one self-contained document with no external requests of any kind — no
 * fonts, no frameworks, no CDN. The browser is talking to a television on the local network, which
 * may have no route to the internet at all, so a page that needed a CDN to render would just fail.
 *
 * Unlike the pairing page this is opened on a phone OR a laptop, so it is responsive and
 * touch-friendly rather than TV-styled. All channel data arrives as JSON and is rendered with
 * `textContent`/DOM APIs (never string-built HTML), so a channel name full of `<`, `"`, `|` or
 * emoji can never break out into markup.
 *
 * ## Language
 *
 * The page is rendered in the browser's language ([WebText]), from the same string resources as the
 * TV's UI. Static markup is filled in on the server (HTML-escaped); the strings the script needs at
 * runtime travel as one JSON object, `T`. Translating in the browser instead — shipping every
 * language and picking one in script — would put 30 copies of the text in each response for the
 * sake of one.
 */
object ManagerPage {

    fun page(token: String, t: WebText): String = """
        <!DOCTYPE html>
        <html lang="${t.tag}" dir="${if (t.rtl) "rtl" else "ltr"}">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
        <meta name="robots" content="noindex, nofollow">
        <title>${t.html(R.string.webpage_title)}</title>
        <style>
          :root { --bg:#07080c; --surface:#12141d; --surface2:#181b26; --line:#2a2f3d;
                  --text:#e8eaf0; --muted:#a8aec0; --accent:#7c93ff; --accent2:#3a447a;
                  --gold:#ffd166; --err:#ff6b6b; --ok:#6ee7a0; }
          * { box-sizing:border-box; -webkit-tap-highlight-color:transparent; }
          body { margin:0; background:var(--bg); color:var(--text);
                 font:16px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; }
          .wrap { max-width:760px; margin:0 auto; padding:20px 16px 80px; }
          header { position:sticky; top:0; z-index:5; background:linear-gradient(var(--bg),var(--bg) 78%,rgba(7,8,12,0));
                   padding-top:8px; }
          h1 { font-size:1.35rem; margin:0; letter-spacing:-.02em; }
          .hint { color:var(--muted); font-size:.85rem; margin:4px 0 14px; }
          .search { width:100%; padding:13px 14px; font-size:17px; margin-bottom:14px;
                    background:var(--surface); color:var(--text); border:1px solid var(--line); border-radius:11px; }
          .search:focus { outline:none; border-color:var(--accent); }
          .chips { display:flex; flex-wrap:wrap; gap:8px; margin-bottom:14px; }
          .chip { padding:9px 14px; font-size:.9rem; border-radius:999px; cursor:pointer;
                  background:var(--surface); border:1px solid var(--line); color:var(--muted); }
          .chip.on { background:var(--accent2); border-color:var(--accent); color:var(--text); }
          .list { display:flex; flex-direction:column; gap:8px; }
          .cat { display:flex; align-items:center; gap:12px; padding:15px 16px; cursor:pointer;
                 background:var(--surface); border:1px solid var(--line); border-radius:12px; }
          .cat:active { background:var(--surface2); }
          .cat .label { flex:1; font-weight:600; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
          .cat .count { color:var(--muted); font-size:.85rem; font-variant-numeric:tabular-nums; }
          .cat .chev { color:var(--muted); }
          .row { display:flex; align-items:center; gap:11px; padding:10px 12px;
                 background:var(--surface); border:1px solid var(--line); border-radius:12px; }
          .row.hidden { opacity:.55; }
          .logo { width:44px; height:44px; border-radius:8px; background:#000; object-fit:contain; flex:none; }
          .rowmeta { flex:1; min-width:0; }
          .name { font-weight:600; cursor:text; display:inline-block; max-width:100%;
                  overflow:hidden; text-overflow:ellipsis; white-space:nowrap; vertical-align:bottom; }
          .name:hover { text-decoration:underline dotted; }
          .rename { width:100%; padding:6px 8px; font-size:16px; font-weight:600;
                    background:var(--bg); color:var(--text); border:1px solid var(--accent); border-radius:8px; }
          .sub { color:var(--muted); font-size:.78rem; margin-top:2px; }
          .sub .badge { color:var(--err); font-weight:600; }
          .ctrls { display:flex; align-items:center; gap:6px; flex:none; }
          .btn { width:40px; height:40px; display:flex; align-items:center; justify-content:center;
                 font-size:18px; border-radius:9px; cursor:pointer; user-select:none;
                 background:var(--surface2); border:1px solid var(--line); color:var(--muted); }
          .btn:active { transform:scale(.92); }
          .btn.star.on { color:var(--gold); border-color:var(--gold); }
          .btn.eye.on { color:var(--accent); border-color:var(--accent); }
          .btn.mv { font-size:14px; }
          .btn[disabled] { opacity:.3; pointer-events:none; }
          .back { display:inline-flex; align-items:center; gap:6px; padding:9px 14px; margin-bottom:12px;
                  background:var(--surface); border:1px solid var(--line); border-radius:10px;
                  color:var(--text); font-weight:600; cursor:pointer; }
          .chanhead { font-size:1.1rem; font-weight:700; margin:0 0 4px; }
          .empty { color:var(--muted); text-align:center; padding:40px 10px; }
          #status { position:fixed; left:50%; bottom:18px; transform:translateX(-50%) translateY(120%);
                    max-width:90%; padding:11px 18px; border-radius:11px; font-size:.9rem; font-weight:600;
                    background:var(--surface2); border:1px solid var(--line); color:var(--text);
                    transition:transform .25s ease; z-index:20; box-shadow:0 8px 24px rgba(0,0,0,.4); }
          #status.show { transform:translateX(-50%) translateY(0); }
          #status.ok { border-color:var(--ok); color:var(--ok); }
          #status.err { border-color:var(--err); color:#ffc9c9; }
          .privacy { color:var(--muted); font-size:.8rem; margin-top:28px; line-height:1.5; }
          details.rec { background:var(--surface); border:1px solid var(--line); border-radius:12px; margin-bottom:16px; }
          details.rec summary { padding:14px 16px; font-weight:600; cursor:pointer; list-style:none; }
          details.rec summary::-webkit-details-marker { display:none; }
          details.rec summary::before { content:'\25B8'; color:var(--muted); margin-inline-end:8px; }
          details.rec[open] summary::before { content:'\25BE'; }
          details.rec .body { padding:0 16px 16px; }
          .field { display:block; margin:10px 0; }
          .field span { display:block; color:var(--muted); font-size:.8rem; margin-bottom:4px; }
          .field input { width:100%; padding:11px 12px; font-size:16px; background:var(--bg); color:var(--text);
                         border:1px solid var(--line); border-radius:9px; }
          .field input:focus { outline:none; border-color:var(--accent); }
          .save { margin-top:6px; padding:12px 18px; font-size:15px; font-weight:600;
                  background:var(--accent2); color:var(--text); border:1px solid var(--accent); border-radius:10px; cursor:pointer; }
          .save:active { transform:scale(.97); }
          .save.secondary { background:var(--surface2); border-color:var(--line); margin-inline-start:8px; }
          .field select { width:100%; padding:11px 12px; font-size:16px; background:var(--bg); color:var(--text);
                          border:1px solid var(--line); border-radius:9px; }
          .field select:focus { outline:none; border-color:var(--accent); }
          /* `.field { display:block }` above would otherwise override the [hidden] attribute, leaving
             a type's irrelevant fields (e.g. username/password on a Stalker portal) on screen. */
          .field[hidden] { display:none; }
          /* Right-to-left languages (Arabic, Persian) mirror the layout through dir="rtl" on <html>.
             What dir cannot mirror is a drawn glyph, so the arrows that mean "forward" and "back"
             are flipped here. The URL, address and login inputs are dir="ltr" in the markup,
             because typing http://… into a right-to-left field scrambles it. */
          [dir=rtl] .chev, [dir=rtl] .backarrow { display:inline-block; transform:scaleX(-1); }
          [dir=rtl] details.rec summary::before { content:'\25C2'; }
          [dir=rtl] details.rec[open] summary::before { content:'\25BE'; }
        </style>
        </head>
        <body>
          <div class="wrap">
            <header>
              <h1>${t.html(R.string.webpage_title)}</h1>
              <p class="hint">${t.html(R.string.webpage_hint)}</p>
            </header>

            <details class="rec" id="provCard">
              <summary>${t.html(R.string.guide_add_provider_button)}</summary>
              <div class="body">
                <p class="hint">${t.html(R.string.webpage_prov_hint)}</p>
                <label class="field"><span>${t.html(R.string.webpage_field_type)}</span>
                  <select id="provKind">
                    <option value="xtream">${t.html(R.string.onboarding_xtream_login)}</option>
                    <option value="m3u">${t.html(R.string.onboarding_m3u_url)}</option>
                    <option value="stalker">${t.html(R.string.onboarding_stalker_portal)}</option>
                  </select></label>
                <label class="field"><span>${t.html(R.string.webpage_field_name_optional)}</span>
                  <input id="provName" placeholder="${t.html(R.string.webpage_prov_name_placeholder)}" autocapitalize="off" autocorrect="off" spellcheck="false"></label>
                <label class="field"><span id="provUrlLabel">${t.html(R.string.onboarding_server_address)}</span>
                  <input id="provUrl" dir="ltr" placeholder="http://example.com:8080" autocapitalize="off" autocorrect="off" spellcheck="false"></label>
                <label class="field" id="provUserField"><span>${t.html(R.string.webpage_field_username)}</span>
                  <input id="provUser" dir="ltr" autocapitalize="off" autocorrect="off" spellcheck="false"></label>
                <label class="field" id="provPassField"><span>${t.html(R.string.webpage_field_password)}</span>
                  <input id="provPass" dir="ltr" type="password" autocomplete="off"></label>
                <label class="field" id="provMacField"><span>${t.html(R.string.onboarding_mac_address)}</span>
                  <input id="provMac" dir="ltr" placeholder="00:1A:79:xx:xx:xx" autocapitalize="off" autocorrect="off" spellcheck="false"></label>
                <button class="save" id="provSave">${t.html(R.string.webpage_prov_add)}</button>
                <button class="save secondary" id="provTest">${t.html(R.string.webpage_test)}</button>
              </div>
            </details>

            <details class="rec" id="nasCard">
              <summary>${t.html(R.string.webpage_nas_title)}</summary>
              <div class="body">
                <p class="hint">${t.html(R.string.webpage_nas_hint)}</p>
                <label class="field"><span>${t.html(R.string.webpage_nas_server)}</span>
                  <input id="nasHost" dir="ltr" placeholder="192.168.0.214" autocapitalize="off" autocorrect="off" spellcheck="false"></label>
                <label class="field"><span>${t.html(R.string.webpage_nas_share)}</span>
                  <input id="nasShare" dir="ltr" placeholder="OpenTV" autocapitalize="off" autocorrect="off" spellcheck="false"></label>
                <label class="field"><span>${t.html(R.string.webpage_nas_folder)}</span>
                  <input id="nasFolder" dir="ltr" placeholder="${t.html(R.string.webpage_nas_folder_placeholder)}" autocapitalize="off" autocorrect="off" spellcheck="false"></label>
                <label class="field"><span>${t.html(R.string.webpage_field_username)}</span>
                  <input id="nasUser" dir="ltr" placeholder="opentv" autocapitalize="off" autocorrect="off" spellcheck="false"></label>
                <label class="field"><span>${t.html(R.string.webpage_field_password)}</span>
                  <input id="nasPass" dir="ltr" type="password" autocomplete="off"></label>
                <button class="save" id="nasSave">${t.html(R.string.webpage_nas_save)}</button>
                <button class="save secondary" id="nasTest">${t.html(R.string.webpage_nas_test)}</button>
              </div>
            </details>

            <section id="catView">
              <div id="srcBar" class="chips" hidden></div>
              <input id="catSearch" class="search" type="text" placeholder="${t.html(R.string.webpage_filter_placeholder)}"
                     autocapitalize="off" autocorrect="off" spellcheck="false">
              <div id="catList" class="list"></div>
              <div id="catEmpty" class="empty" hidden></div>
            </section>

            <section id="chanView" hidden>
              <div id="back" class="back"><span class="backarrow">&#8249;</span>&nbsp;${t.html(R.string.channels_manager_categories_header)}</div>
              <h2 id="chanHead" class="chanhead"></h2>
              <p class="hint" id="chanHint">${t.html(R.string.webpage_channels_hint)}</p>
              <div id="chanList" class="list"></div>
              <div id="chanEmpty" class="empty" hidden></div>
            </section>

            <p class="privacy">${t.html(R.string.webpage_privacy)}</p>
          </div>

          <div id="status"></div>

        <script>
          var EMBEDDED_TOKEN = "$token";
          var token = new URLSearchParams(location.search).get('t') || EMBEDDED_TOKEN;

          // Every string the script shows, already in the page's language. fmt() fills the
          // positional placeholders (%1${'$'}d, %1${'$'}s) that the same Android resources use on the TV.
          var T = ${dictionary(t)};
          function fmt(s){ var a = arguments; return s.replace(/%(\d+)[$][sd]/g, function(_, n){ return a[+n]; }); }

          var meta = { sources: [], categories: [] };
          var currentSource = null;   // selected source filter, null = all
          var currentCat = null;      // the category object being viewed
          var channels = [];          // rows currently shown, in display order

          var el = function(id){ return document.getElementById(id); };
          var statusTimer = null;
          function setStatus(msg, kind){
            var s = el('status');
            s.textContent = msg;
            s.className = 'show ' + (kind || '');
            if (statusTimer) clearTimeout(statusTimer);
            statusTimer = setTimeout(function(){ s.className = kind || ''; }, 2600);
          }

          function withToken(path){ return path + (path.indexOf('?') >= 0 ? '&' : '?') + 't=' + encodeURIComponent(token); }
          async function getJson(path){
            var r = await fetch(withToken(path));
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
          }
          async function post(path, bodyObj){
            var r = await fetch(withToken(path), {
              method: 'POST', headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify(bodyObj)
            });
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
          }
          var UNREACH = T.unreachable;

          // ---- Categories --------------------------------------------------------------------
          async function loadMeta(){
            try {
              meta = await getJson('/meta');
              renderSources();
              renderCategories();
            } catch (e) {
              el('catList').innerHTML = '';
              var d = el('catEmpty'); d.hidden = false; d.textContent = UNREACH;
            }
          }

          function renderSources(){
            var bar = el('srcBar');
            if (!meta.sources || meta.sources.length < 2) { bar.hidden = true; return; }
            bar.hidden = false;
            bar.innerHTML = '';
            var mk = function(label, id){
              var c = document.createElement('div');
              c.className = 'chip' + (currentSource === id ? ' on' : '');
              c.textContent = label;
              c.onclick = function(){ currentSource = id; renderSources(); renderCategories(); };
              bar.appendChild(c);
            };
            mk(T.all_sources, null);
            meta.sources.forEach(function(s){ mk(s.name, s.id); });
          }

          function renderCategories(){
            var q = el('catSearch').value.trim().toLowerCase();
            var list = el('catList');
            list.innerHTML = '';
            var cats = (meta.categories || []).filter(function(c){
              if (currentSource !== null && c.sourceId !== currentSource) return false;
              if (q && c.label.toLowerCase().indexOf(q) < 0) return false;
              return true;
            });
            var empty = el('catEmpty');
            if (cats.length === 0) {
              empty.hidden = false;
              empty.textContent = (meta.categories && meta.categories.length) ? T.no_categories_match : T.no_channels_yet;
              return;
            }
            empty.hidden = true;
            cats.forEach(function(c){
              var row = document.createElement('div');
              row.className = 'cat';
              var label = document.createElement('span'); label.className = 'label'; label.textContent = c.label;
              var count = document.createElement('span'); count.className = 'count'; count.textContent = c.count;
              var chev = document.createElement('span'); chev.className = 'chev'; chev.innerHTML = '&#8250;';
              row.appendChild(label); row.appendChild(count); row.appendChild(chev);
              row.onclick = function(){ openCategory(c); };
              list.appendChild(row);
            });
          }
          el('catSearch').addEventListener('input', renderCategories);

          // ---- Channels ----------------------------------------------------------------------
          async function openCategory(cat){
            currentCat = cat;
            el('catView').hidden = true;
            el('chanView').hidden = false;
            el('chanHead').textContent = cat.label;
            window.scrollTo(0, 0);
            var listEl = el('chanList'); listEl.innerHTML = '';
            var empty = el('chanEmpty'); empty.hidden = true;
            try {
              channels = await getJson('/channels?cat=' + encodeURIComponent(cat.id) + '&source=' + cat.sourceId);
              renderChannels();
            } catch (e) {
              empty.hidden = false; empty.textContent = UNREACH;
            }
          }

          el('back').onclick = function(){
            el('chanView').hidden = true;
            el('catView').hidden = false;
            channels = [];
          };

          function renderChannels(){
            var listEl = el('chanList');
            listEl.innerHTML = '';
            var empty = el('chanEmpty');
            if (!channels.length) { empty.hidden = false; empty.textContent = T.empty_category; return; }
            empty.hidden = true;
            channels.forEach(function(c, i){ listEl.appendChild(buildRow(c, i)); });
          }

          function buildRow(c, index){
            var row = document.createElement('div');
            row.className = 'row' + (c.hidden ? ' hidden' : '');

            var logo = document.createElement('img');
            logo.className = 'logo'; logo.alt = '';
            if (c.logo) { logo.src = c.logo; } else { logo.style.visibility = 'hidden'; }
            logo.onerror = function(){ logo.style.visibility = 'hidden'; };

            var mid = document.createElement('div'); mid.className = 'rowmeta';
            var name = document.createElement('span'); name.className = 'name'; name.textContent = c.name;
            name.onclick = function(){ startRename(name, c); };
            var sub = document.createElement('div'); sub.className = 'sub';
            mid.appendChild(name); mid.appendChild(sub);
            function refreshSub(){
              sub.innerHTML = '';
              if (c.number !== null && c.number !== undefined) {
                sub.appendChild(document.createTextNode(fmt(T.channel_number, c.number)));
              }
              if (c.hidden) {
                if (sub.childNodes.length) sub.appendChild(document.createTextNode('  ·  '));
                var b = document.createElement('span'); b.className = 'badge'; b.textContent = T.hidden;
                sub.appendChild(b);
              }
              if (!sub.childNodes.length) sub.textContent = c.original !== c.name ? c.original : ' ';
            }
            refreshSub();

            var ctrls = document.createElement('div'); ctrls.className = 'ctrls';

            var star = document.createElement('div');
            star.className = 'btn star' + (c.favourite ? ' on' : '');
            star.innerHTML = c.favourite ? '&#9733;' : '&#9734;';
            star.title = T.favourite;
            star.onclick = function(){
              var target = !c.favourite;
              post('/channel', { id: c.id, favourite: target })
                .then(function(){ c.favourite = target; star.className = 'btn star' + (target ? ' on' : ''); star.innerHTML = target ? '&#9733;' : '&#9734;'; setStatus(target ? T.favourited : T.unfavourited, 'ok'); })
                .catch(function(){ setStatus(UNREACH, 'err'); });
            };

            var eye = document.createElement('div');
            eye.className = 'btn eye' + (c.hidden ? '' : ' on');
            eye.innerHTML = c.hidden ? '&#128584;' : '&#128065;';
            eye.title = c.hidden ? T.hidden_tap_show : T.showing_tap_hide;
            eye.onclick = function(){
              var target = !c.hidden;
              post('/channel', { id: c.id, hidden: target })
                .then(function(){
                  c.hidden = target;
                  row.className = 'row' + (target ? ' hidden' : '');
                  eye.className = 'btn eye' + (target ? '' : ' on');
                  eye.innerHTML = target ? '&#128584;' : '&#128065;';
                  eye.title = target ? T.hidden_tap_show : T.showing_tap_hide;
                  refreshSub();
                  setStatus(target ? T.hidden_from_guide : T.showing_in_guide, 'ok');
                })
                .catch(function(){ setStatus(UNREACH, 'err'); });
            };

            var up = document.createElement('div');
            up.className = 'btn mv'; up.innerHTML = '&#9650;'; up.title = T.move_up;
            if (index === 0) up.setAttribute('disabled', '');
            up.onclick = function(){ move(index, index - 1); };

            var down = document.createElement('div');
            down.className = 'btn mv'; down.innerHTML = '&#9660;'; down.title = T.move_down;
            if (index === channels.length - 1) down.setAttribute('disabled', '');
            down.onclick = function(){ move(index, index + 1); };

            ctrls.appendChild(star); ctrls.appendChild(eye); ctrls.appendChild(up); ctrls.appendChild(down);
            row.appendChild(logo); row.appendChild(mid); row.appendChild(ctrls);
            return row;
          }

          function startRename(span, c){
            var input = document.createElement('input');
            input.type = 'text'; input.className = 'rename'; input.value = c.name;
            span.replaceWith(input); input.focus(); input.select();
            var closed = false;
            function finish(save){
              if (closed) return; closed = true;
              var val = input.value.trim();
              if (save) {
                c.name = val || c.original;
                post('/channel', { id: c.id, name: val })
                  .then(function(){ setStatus(T.renamed, 'ok'); })
                  .catch(function(){ setStatus(T.rename_failed, 'err'); });
              }
              var fresh = document.createElement('span');
              fresh.className = 'name'; fresh.textContent = c.name;
              fresh.onclick = function(){ startRename(fresh, c); };
              input.replaceWith(fresh);
            }
            input.addEventListener('keydown', function(e){
              if (e.key === 'Enter') { e.preventDefault(); finish(true); }
              else if (e.key === 'Escape') { finish(false); }
            });
            input.addEventListener('blur', function(){ finish(true); });
          }

          function move(from, to){
            if (to < 0 || to >= channels.length) return;
            var item = channels.splice(from, 1)[0];
            channels.splice(to, 0, item);
            renderChannels();
            post('/reorder', { ids: channels.map(function(c){ return c.id; }) })
              .then(function(){ setStatus(T.order_saved, 'ok'); })
              .catch(function(){ setStatus(UNREACH, 'err'); });
          }

          // ---- NAS (sync) settings -----------------------------------------------------------
          async function loadNas(){
            try {
              var r = await getJson('/nas');
              el('nasHost').value = r.host || '';
              el('nasShare').value = r.share || '';
              el('nasFolder').value = r.folder || '';
              el('nasUser').value = r.user || '';
              el('nasPass').placeholder = r.hasPassword ? T.nas_password_unchanged : '';
              if (r.host) el('nasCard').open = true;
            } catch (e) { /* leave the form blank if the TV can't be reached yet */ }
          }
          function smbBody(){
            return {
              host: el('nasHost').value.trim(),
              share: el('nasShare').value.trim(),
              folder: el('nasFolder').value.trim(),
              user: el('nasUser').value.trim(),
              password: el('nasPass').value
            };
          }
          function showTest(r){
            if (r && r.ok) setStatus(T.nas_connected, 'ok');
            else setStatus(fmt(T.nas_failed, (r && r.error) || T.nas_check_details), 'err');
          }
          el('nasSave').onclick = function(){
            var body = smbBody();
            if (!body.host || !body.share) { setStatus(T.nas_required, 'err'); return; }
            setStatus(T.nas_saving);
            post('/nas', body)
              .then(function(){
                el('nasPass').value = '';
                el('nasPass').placeholder = T.nas_password_unchanged;
                return post('/nas/test', {});   // test the settings we just stored
              })
              .then(showTest)
              .catch(function(){ setStatus(UNREACH, 'err'); });
          };
          el('nasTest').onclick = function(){
            setStatus(T.testing);
            post('/nas/test', smbBody())
              .then(showTest)
              .catch(function(){ setStatus(UNREACH, 'err'); });
          };

          // ---- Add a provider ----------------------------------------------------------------
          function provType(){ return el('provKind').value; }
          function syncProvFields(){
            var k = provType();
            el('provUserField').hidden = (k !== 'xtream');
            el('provPassField').hidden = (k !== 'xtream');
            el('provMacField').hidden = (k !== 'stalker');
            el('provUrlLabel').textContent = (k === 'm3u') ? T.playlist_url : (k === 'stalker' ? T.portal_url : T.server_address);
            el('provUrl').placeholder = (k === 'm3u') ? 'http://example.com/playlist.m3u'
              : (k === 'stalker' ? 'http://portal.example.com:80/c' : 'http://example.com:8080');
          }
          el('provKind').addEventListener('change', syncProvFields);
          syncProvFields();

          function provBody(){
            return { kind: provType(), name: el('provName').value.trim(), url: el('provUrl').value.trim(),
                     username: el('provUser').value.trim(), password: el('provPass').value, mac: el('provMac').value.trim() };
          }
          function provInvalid(b){
            if (!b.url) return T.enter_url;
            if (b.kind === 'xtream' && (!b.username || !b.password)) return T.user_pass_required;
            if (b.kind === 'stalker' && !b.mac) return T.mac_required;
            return null;
          }
          el('provTest').onclick = function(){
            var b = provBody(); var bad = provInvalid(b); if (bad){ setStatus(bad, 'err'); return; }
            setStatus(T.testing);
            post('/source/test', b)
              .then(function(r){ if (r && r.ok) setStatus(r.message || T.test_ok_default, 'ok'); else setStatus((r && r.error) || T.test_failed, 'err'); })
              .catch(function(){ setStatus(UNREACH, 'err'); });
          };
          el('provSave').onclick = function(){
            var b = provBody(); var bad = provInvalid(b); if (bad){ setStatus(bad, 'err'); return; }
            setStatus(T.adding);
            post('/source', b)
              .then(function(r){
                if (r && r.ok){
                  setStatus(fmt(T.added, r.channels || 0), 'ok');
                  el('provUrl').value = ''; el('provUser').value = ''; el('provPass').value = '';
                  el('provMac').value = ''; el('provName').value = '';
                  loadMeta();
                } else setStatus((r && r.error) || T.add_failed, 'err');
              })
              .catch(function(){ setStatus(UNREACH, 'err'); });
          };

          loadMeta();
          loadNas();
        </script>
        </body>
        </html>
    """.trimIndent()

    private const val BACKSLASH = "\\"

    /**
     * Makes a JSON text safe to sit inside a `<script>` block. Every `<` becomes a unicode escape,
     * so no string — however a translator words it — can contain `</script>` and end the block
     * early. The line and paragraph separators (U+2028/2029) are escaped too: they are legal in
     * JSON but were line terminators inside a JavaScript string for a long time.
     */
    internal fun escapeForScript(json: String): String = json
        .replace("<", BACKSLASH + "u003c")
        .replace(Char(0x2028).toString(), BACKSLASH + "u2028")
        .replace(Char(0x2029).toString(), BACKSLASH + "u2029")

    /**
     * The strings the script needs, as a JSON object literal. Built with a JSON encoder so quotes,
     * backslashes and newlines in a translation cannot break the literal.
     */
    private fun dictionary(t: WebText): String = escapeForScript(buildJsonObject {
        put("unreachable", t.get(R.string.webpage_unreachable))
        put("all_sources", t.get(R.string.channels_manager_all_sources))
        put("no_categories_match", t.get(R.string.webpage_no_categories_match))
        put("no_channels_yet", t.get(R.string.webpage_no_channels_yet))
        put("empty_category", t.get(R.string.channels_manager_empty_category))
        put("channel_number", t.get(R.string.webpage_channel_number))
        put("hidden", t.get(R.string.channels_hidden))
        put("favourite", t.get(R.string.common_favourite))
        put("hidden_tap_show", t.get(R.string.webpage_hidden_tap_show))
        put("showing_tap_hide", t.get(R.string.webpage_showing_tap_hide))
        put("move_up", t.get(R.string.webpage_move_up))
        put("move_down", t.get(R.string.webpage_move_down))
        put("favourited", t.get(R.string.webpage_favourited))
        put("unfavourited", t.get(R.string.webpage_unfavourited))
        put("hidden_from_guide", t.get(R.string.webpage_hidden_from_guide))
        put("showing_in_guide", t.get(R.string.webpage_showing_in_guide))
        put("renamed", t.get(R.string.webpage_renamed))
        put("rename_failed", t.get(R.string.webpage_rename_failed))
        put("order_saved", t.get(R.string.webpage_order_saved))
        put("nas_password_unchanged", t.get(R.string.webpage_nas_password_unchanged))
        put("nas_connected", t.get(R.string.webpage_nas_connected))
        put("nas_failed", t.get(R.string.webpage_nas_failed))
        put("nas_check_details", t.get(R.string.webpage_nas_check_details))
        put("nas_required", t.get(R.string.webpage_nas_required))
        put("nas_saving", t.get(R.string.webpage_nas_saving))
        put("testing", t.get(R.string.webpage_testing))
        put("server_address", t.get(R.string.onboarding_server_address))
        put("playlist_url", t.get(R.string.onboarding_playlist_url))
        put("portal_url", t.get(R.string.onboarding_portal_url))
        put("enter_url", t.get(R.string.webpage_enter_url))
        put("user_pass_required", t.get(R.string.webpage_user_pass_required))
        put("mac_required", t.get(R.string.webpage_mac_required))
        put("test_ok_default", t.get(R.string.webpage_test_ok_default))
        put("test_failed", t.get(R.string.webpage_test_failed))
        put("adding", t.get(R.string.webpage_adding))
        put("added", t.get(R.string.webpage_added))
        put("add_failed", t.get(R.string.webpage_add_failed))
    }.toString())
}
