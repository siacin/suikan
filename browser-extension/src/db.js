let opened;
function db() {
  if (!opened) opened = new Promise((resolve, reject) => {
    const request = indexedDB.open('suikan-browser', 1);
    request.onupgradeneeded = () => request.result.createObjectStore('notes', { keyPath: 'id' });
    request.onsuccess = () => resolve(request.result); request.onerror = () => reject(new Error('本地笔记库无法打开'));
  });
  return opened;
}
async function transact(mode, action) {
  const database = await db();
  return new Promise((resolve, reject) => {
    const transaction = database.transaction('notes', mode), store = transaction.objectStore('notes'); let result;
    try { result = action(store); } catch (error) { reject(error); return; }
    transaction.oncomplete = () => resolve(result?.result);
    transaction.onerror = () => reject(new Error('笔记存储失败，请导出笔记并检查浏览器存储空间'));
    transaction.onabort = () => reject(new Error('笔记保存被中止'));
  });
}
export async function listNotes() { return (await transact('readonly', store => store.getAll())).sort((a, b) => b.savedAt - a.savedAt); }
export async function saveNote(note) {
  await transact('readwrite', store => store.put(note));
  const notes = await listNotes();
  if (notes.length > 30) await transact('readwrite', store => { for (const row of notes.slice(30)) store.delete(row.id); });
}
export async function deleteNote(id) { await transact('readwrite', store => store.delete(id)); }
