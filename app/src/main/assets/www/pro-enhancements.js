/* Quiz Studio Pro: durable project autosave, project CRUD and full-format imports. */
(function () {
  'use strict';
  const $ = id => document.getElementById(id);
  const DB_NAME = 'quiz-studio-pro-db', STORE = 'projects';
  let dbPromise, activeId = localStorage.getItem('qsp_active_project') || 'draft', pendingImport = null, clearBulkAfterImport = false;
  const uid = () => (crypto.randomUUID ? crypto.randomUUID() : 'qsp-' + Date.now() + '-' + Math.random().toString(16).slice(2));
  function db() {
    if (dbPromise) return dbPromise;
    dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, 1);
      req.onupgradeneeded = () => { const d = req.result; if (!d.objectStoreNames.contains(STORE)) d.createObjectStore(STORE, { keyPath: 'id' }); };
      req.onsuccess = () => resolve(req.result); req.onerror = () => reject(req.error);
    });
    return dbPromise;
  }
  async function put(record) { const d = await db(); return new Promise((res, rej) => { const tx = d.transaction(STORE, 'readwrite'); tx.objectStore(STORE).put(record); tx.oncomplete = () => res(); tx.onerror = () => rej(tx.error); }); }
  async function get(id) { const d = await db(); return new Promise((res, rej) => { const r = d.transaction(STORE).objectStore(STORE).get(id); r.onsuccess = () => res(r.result || null); r.onerror = () => rej(r.error); }); }
  async function all() { const d = await db(); return new Promise((res, rej) => { const r = d.transaction(STORE).objectStore(STORE).getAll(); r.onsuccess = () => res(r.result || []); r.onerror = () => rej(r.error); }); }
  async function remove(id) { const d = await db(); return new Promise((res, rej) => { const tx = d.transaction(STORE, 'readwrite'); tx.objectStore(STORE).delete(id); tx.oncomplete = res; tx.onerror = () => rej(tx.error); }); }
  function snapshot() {
    return { id: activeId, title: $('projectTitle').value || 'Untitled Quiz', channelName: $('channelName').value || '',
      headerText: $('headerText').value || '', settings: currentTemplate(), questions: JSON.parse(JSON.stringify(questions)),
      updatedAt: new Date().toISOString(), schemaVersion: 1 };
  }
  async function save(silent) {
    try { await put(snapshot()); if ($('saveStatus')) $('saveStatus').textContent = silent ? 'Auto-saved' : 'Project saved'; }
    catch (e) { if ($('saveStatus')) $('saveStatus').textContent = 'Save failed: ' + e.message; }
  }
  let saveTimer;
  function scheduleSave() { clearTimeout(saveTimer); saveTimer = setTimeout(() => save(true), 650); }
  function restore(p) {
    if (!p) return;
    activeId = p.id; localStorage.setItem('qsp_active_project', activeId);
    $('projectTitle').value = p.title || 'Imported Quiz'; $('channelName').value = p.channelName || ''; $('headerText').value = p.headerText || '';
    questions.splice(0, questions.length, ...(Array.isArray(p.questions) ? p.questions : []));
    current = questions.length ? 0 : -1;
    if (p.settings) applyTemplate(p.settings);
    renderList(); renderPreview();
    if ($('saveStatus')) $('saveStatus').textContent = 'Project loaded';
  }
  function download(name, content, type) {
    const blob = new Blob([content], { type }); const url = URL.createObjectURL(blob); const a = document.createElement('a');
    a.href = url; a.download = name; a.click(); setTimeout(() => URL.revokeObjectURL(url), 30000);
  }
  function csvEscape(v) { const s = String(v ?? ''); return /[",\r\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s; }
  function showManager() {
    let modal = $('qspProjectModal'); if (!modal) return;
    modal.classList.remove('hidden'); refreshProjects();
  }
  async function refreshProjects() {
    const list = $('qspProjectList'); if (!list) return; list.innerHTML = '<p class="hint">Loading projects…</p>';
    try {
      const items = (await all()).sort((a,b) => String(b.updatedAt).localeCompare(String(a.updatedAt)));
      list.innerHTML = '';
      if (!items.length) { list.textContent = 'No saved projects yet.'; return; }
      items.forEach(p => {
        const row = document.createElement('div'); row.className = 'q-item';
        const label = document.createElement('span'); label.className = 'qtext'; label.textContent = `${p.title} · ${(p.questions || []).length} questions`;
        const load = document.createElement('button'); load.className = 'btn mini'; load.textContent = 'Open'; load.onclick = async () => { restore(await get(p.id)); $('qspProjectModal').classList.add('hidden'); };
        const dup = document.createElement('button'); dup.className = 'btn mini'; dup.textContent = 'Copy'; dup.onclick = async () => { const copy = structuredClone(p); copy.id = uid(); copy.title += ' (Copy)'; copy.updatedAt = new Date().toISOString(); await put(copy); refreshProjects(); };
        const rename = document.createElement('button'); rename.className = 'btn mini'; rename.textContent = 'Rename'; rename.onclick = async () => { const name = prompt('New project name', p.title); if (name && name.trim()) { p.title = name.trim().slice(0,100); await put(p); refreshProjects(); } };
        const del = document.createElement('button'); del.className = 'btn mini ghost'; del.textContent = 'Delete'; del.onclick = async () => { if (confirm('Delete "' + p.title + '"?')) { await remove(p.id); if (activeId === p.id) activeId = 'draft'; refreshProjects(); } };
        row.append(label, load, dup, rename, del); list.appendChild(row);
      });
    } catch (e) { list.textContent = 'Could not load projects: ' + e.message; }
  }
  function showImportPreview(parsed, clearBulk) {
    pendingImport = parsed; clearBulkAfterImport = !!clearBulk;
    let modal = $('qspImportModal');
    if (!modal) {
      modal = document.createElement('div'); modal.id = 'qspImportModal';
      modal.style.cssText = 'position:fixed;inset:0;background:#000d;z-index:10000;display:flex;align-items:center;justify-content:center;padding:14px';
      modal.innerHTML = '<div style="width:min(760px,100%);max-height:90vh;overflow:auto;background:#111;border:1px solid #444;border-radius:14px;padding:18px"><div class="row" style="justify-content:space-between"><h2 style="margin:0">Import Preview</h2><button class="btn" id="qspImportCancel">Cancel</button></div><p id="qspImportSummary" class="hint"></p><div id="qspImportRows" class="q-list"></div><div class="row" style="justify-content:flex-end;margin-top:14px"><button class="btn primary" id="qspImportConfirm">Import valid questions</button></div></div>';
      document.body.appendChild(modal);
      $('qspImportCancel').onclick = () => { pendingImport = null; modal.classList.add('hidden'); };
      $('qspImportConfirm').onclick = () => {
        if (!pendingImport) return;
        const existing = new Set(questions.map(q => (q.question + '|' + q.options.join('|') + '|' + q.correct).toLowerCase()));
        let added = 0, duplicates = pendingImport.duplicates;
        pendingImport.questions.forEach(q => { const key = (q.question + '|' + q.options.join('|') + '|' + q.correct).toLowerCase(); if (existing.has(key)) { duplicates++; return; } existing.add(key); questions.push(q); added++; });
        if (clearBulkAfterImport) $('bulkInput').value = '';
        if (added) { current = questions.length - 1; renderList(); renderPreview(); scheduleSave(); }
        const errors = pendingImport.errors.length; pendingImport = null; modal.classList.add('hidden');
        alert(`Imported: ${added}\nDuplicates skipped: ${duplicates}\nInvalid rows: ${errors}`);
      };
    }
    $('qspImportSummary').textContent = `${parsed.questions.length} valid question(s) · ${parsed.duplicates} duplicate(s) inside file · ${parsed.errors.length} invalid row(s)`;
    const rows = $('qspImportRows'); rows.innerHTML = '';
    parsed.questions.slice(0, 12).forEach((q,i) => { const row = document.createElement('div'); row.className = 'q-item'; const txt = document.createElement('span'); txt.className = 'qtext'; txt.style.whiteSpace = 'normal'; txt.textContent = `${i+1}. ${q.question} — ${q.options.join(' / ')} — Correct: ${'ABCD'[q.correct]}`; row.appendChild(txt); rows.appendChild(row); });
    if (parsed.questions.length > 12) { const more = document.createElement('p'); more.className = 'hint'; more.textContent = `And ${parsed.questions.length - 12} more valid questions.`; rows.appendChild(more); }
    parsed.errors.slice(0, 12).forEach(err => { const row = document.createElement('div'); row.className = 'q-item'; row.style.color = '#ffb8b3'; row.textContent = err; rows.appendChild(row); });
    if (parsed.errors.length > 12) { const more = document.createElement('p'); more.className = 'hint'; more.textContent = `And ${parsed.errors.length - 12} more invalid rows.`; rows.appendChild(more); }
    modal.classList.remove('hidden');
  }

  function addToolbar() {
    const header = document.querySelector('header');
    const session = (() => { try { return JSON.parse(localStorage.getItem('qsp_session') || 'null'); } catch (_) { return null; } })();
    const account = document.createElement('span'); account.className = 'badge'; account.textContent = session && session.username ? ('ACCOUNT · ' + session.username) : 'LOCAL MODE'; header.appendChild(account);
    const logout = document.createElement('button'); logout.className = 'hbtn'; logout.textContent = session && session.username ? 'Logout' : 'Account'; logout.onclick = async () => {
      if (!session || !session.token) { location.href = 'login.html'; return; }
      try { const base = (localStorage.getItem('qsp_account_base_url') || '').replace(/\/+$/, ''); if (base) await fetch(base + '/account/logout', {method:'POST',headers:{Authorization:'Bearer '+session.token}}); } catch (_) {}
      localStorage.removeItem('qsp_session'); location.href = 'login.html';
    }; header.appendChild(logout);
    if (session && session.token) {
      const changePassword = document.createElement('button'); changePassword.className = 'hbtn'; changePassword.textContent = 'Change password';
      changePassword.onclick = async () => {
        const oldPassword = prompt('Current password'); if (!oldPassword) return;
        const newPassword = prompt('New password (at least 12 characters)'); if (!newPassword) return;
        if (newPassword.length < 12) { alert('Use at least 12 characters.'); return; }
        const confirmPassword = prompt('Confirm new password'); if (newPassword !== confirmPassword) { alert('Passwords do not match.'); return; }
        try {
          const base = (localStorage.getItem('qsp_account_base_url') || '').replace(/\/+$/, '');
          const deviceId = window.AndroidBridge && AndroidBridge.deviceId ? AndroidBridge.deviceId() : 'web-local-device';
          const response = await fetch(base + '/account/change-password', {method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+session.token},body:JSON.stringify({oldPassword,newPassword,deviceId})});
          const result = await response.json(); if (!response.ok) throw new Error(result.detail || 'Password change failed');
          localStorage.removeItem('qsp_session'); alert('Password changed. Please sign in again.'); location.href = 'login.html';
        } catch (e) { alert('Password change failed: ' + e.message); }
      };
      header.appendChild(changePassword);
    }
    const manager = document.createElement('button'); manager.className = 'hbtn'; manager.textContent = '▤ Projects'; manager.onclick = showManager; header.appendChild(manager);
    const saveBtn = document.createElement('button'); saveBtn.className = 'hbtn'; saveBtn.textContent = 'Save'; saveBtn.onclick = () => save(false); header.appendChild(saveBtn);
    const exportBtn = document.createElement('button'); exportBtn.className = 'hbtn'; exportBtn.textContent = 'Export Questions'; exportBtn.onclick = () => {
      const data = questions.map((q,i) => ({ number: i + 1, question: q.question, options: q.options, correct: 'ABCD'[q.correct] }));
      download('quiz-questions.json', JSON.stringify(data, null, 2), 'application/json');
    }; header.appendChild(exportBtn);
    const exportCsv = document.createElement('button'); exportCsv.className = 'hbtn'; exportCsv.textContent = 'Export CSV'; exportCsv.onclick = () => {
      const escape = value => { const v = String(value ?? ''); return /[",\r\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v; };
      const rows = [['No.','Question','Option A','Option B','Option C','Option D','Correct'], ...questions.map((q,i) => [i+1,q.question,...q.options,'ABCD'[q.correct]])];
      download('quiz-questions.csv', rows.map(row => row.map(escape).join(',')).join('\r\n'), 'text/csv;charset=utf-8');
    }; header.appendChild(exportCsv);
    const importBtn = document.createElement('button'); importBtn.className = 'hbtn'; importBtn.textContent = 'Import CSV/JSON';
    const file = document.createElement('input'); file.type = 'file'; file.accept = '.csv,.tsv,.txt,.json,.md,.markdown,text/csv,application/json,text/plain'; file.hidden = true;
    importBtn.onclick = () => file.click(); file.onchange = async () => {
      const f = file.files && file.files[0]; file.value = ''; if (!f) return;
      try {
        const parsed = QuizStudioImport.parseText(await f.text(), f.name.toLowerCase().endsWith('.json') ? 'json' : 'text');
        showImportPreview(parsed, false);
      } catch (e) { alert('Import failed: ' + e.message); }
    };
    header.append(importBtn, file);
    const modal = document.createElement('div'); modal.id = 'qspProjectModal'; modal.className = 'hidden';
    modal.style.cssText = 'position:fixed;inset:0;background:#000c;z-index:9998;display:flex;align-items:center;justify-content:center;padding:16px';
    modal.innerHTML = '<div style="width:min(760px,100%);max-height:90vh;overflow:auto;background:#111;border:1px solid #444;border-radius:14px;padding:18px"><div class="row" style="justify-content:space-between"><h2 style="margin:0">Quiz Projects</h2><button class="btn" id="qspProjectClose">Close</button></div><p class="hint">Projects are stored locally on this device using IndexedDB.</p><div id="qspProjectList" class="q-list"></div></div>';
    document.body.appendChild(modal); $('qspProjectClose').onclick = () => modal.classList.add('hidden');
  }
  // Narrate the question and all four options before the timer, then narrate the answer after reveal.
  // Direct Edge TTS narration is managed natively by index.html.

  async function validateAccountSession() {
    let session; try { session = JSON.parse(localStorage.getItem('qsp_session') || 'null'); } catch (_) { session = null; }
    const base = (localStorage.getItem('qsp_account_base_url') || '').replace(/\/+$/, '');
    if (!session || !session.token || !base) return;
    try {
      const deviceId = window.AndroidBridge && AndroidBridge.deviceId ? AndroidBridge.deviceId() : 'web-local-device';
      const response = await fetch(base + '/account/validate', {method:'POST',headers:{Authorization:'Bearer '+session.token,'X-Device-ID':deviceId}});
      if (!response.ok) { localStorage.removeItem('qsp_session'); alert('Your account session is no longer valid. The app will continue in local mode.'); location.href = 'login.html'; }
    } catch (_) { /* Account service outages do not block local editing or local project access. */ }
  }

  async function init() {
    addToolbar();
    validateAccountSession();
    setInterval(validateAccountSession, 5 * 60 * 1000);
    document.addEventListener('visibilitychange', () => { if (!document.hidden) validateAccountSession(); });
    const importButton = $('importBulk');
    const oldHandler = importButton.onclick;
    importButton.onclick = function () {
      if (typeof editing !== 'undefined' && editing >= 0) return oldHandler.call(this);
      const text = $('bulkInput').value;
      if (!text.trim()) { alert('Paste questions into the box first.'); return; }
      const parsed = QuizStudioImport.parseText(text, /^\s*[\[{]/.test(text.trim()) ? 'json' : 'text');
      if (parsed.errors && parsed.errors.length) {
        showImportPreview(parsed, true);
      } else if (parsed.questions && parsed.questions.length) {
        const existing = new Set(questions.map(q => (q.question + '|' + q.options.join('|') + '|' + q.correct).toLowerCase()));
        let added = 0, duplicates = parsed.duplicates || 0;
        parsed.questions.forEach(q => {
          const key = (q.question + '|' + q.options.join('|') + '|' + q.correct).toLowerCase();
          if (existing.has(key)) { duplicates++; return; }
          existing.add(key);
          questions.push(q);
          added++;
        });
        $('bulkInput').value = '';
        if (added) {
          current = questions.length - 1;
          renderList();
          renderPreview();
          scheduleSave();
        }
        alert(`Imported ${added} question(s).${duplicates ? ' (' + duplicates + ' duplicates skipped)' : ''}`);
      } else {
        showImportPreview(parsed, true);
      }
    };
    document.addEventListener('input', scheduleSave, true);
    document.addEventListener('change', scheduleSave, true);
    document.addEventListener('click', e => { if (e.target.closest('button')) scheduleSave(); }, true);
    const newProjectButton = $('newProject'); const oldNewProject = newProjectButton.onclick;
    newProjectButton.onclick = async function (event) {
      const beforeCount = questions.length; await save(true);
      if (oldNewProject) oldNewProject.call(this, event);
      if (beforeCount !== questions.length || beforeCount === 0) {
        activeId = uid(); localStorage.setItem('qsp_active_project', activeId); await save(true);
      }
    };
    const existing = await get(activeId);
    if (existing) restore(existing);
    else { activeId = activeId === 'draft' ? uid() : activeId; localStorage.setItem('qsp_active_project', activeId); await save(true); }
    // Recover an unfinished project automatically on the next launch.
    window.addEventListener('beforeunload', () => { try { const data = snapshot(); const tx = dbPromise; if (tx) tx.then(d => d.transaction(STORE, 'readwrite').objectStore(STORE).put(data)); } catch (_) {} });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', () => init().catch(e => console.error(e)));
  else init().catch(e => console.error(e));
})();
