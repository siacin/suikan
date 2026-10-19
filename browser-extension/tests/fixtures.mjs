export const BV = 'BV1bApq6LEeS', LINK = 'https://www.bilibili.com/video/' + BV;
export function fixture() {
  return {
    views: 0, downloads: 0, requests: [],
    view: { code: 0, data: { bvid: BV, aid: 117433935334009, title: '【什么是2.0】', owner: { name: '测试作者' }, pages: [{ page: 1, cid: 101, duration: 60, part: '第一P' }, { page: 2, cid: 202, duration: 90, part: '第二P' }] } },
    player: { code: 0, data: { bvid: BV, aid: 117433935334009, cid: 101, page_no: 1, subtitle: { subtitles: [{ id_str: '2127287793456724992', lan: 'zh-CN', subtitle_url: 'https://aisubtitle.hdslb.com/bfs/subtitle/fixture.json' }] } } },
    subtitle: { body: [{ from: 0, to: 2.1, content: '字幕开头' }, { from: 50, to: 55, content: '字幕末尾' }] },
    async resolve() { return LINK; },
    async get(url) {
      this.requests.push(url);
      if (url.includes('/x/web-interface/view')) { this.views++; const view = structuredClone(this.view); if (this.views > 1 && this.mutateTitle) view.data.title = '其他视频'; return view; }
      if (url.includes('/x/player/')) return structuredClone(this.player);
      this.downloads++; return structuredClone(this.subtitle);
    }
  };
}
export const source = { title: '完整视频测试', sourceKind: 'bili', bvid: BV, cid: 101, page: 1, url: LINK,
  segments: [{ id: 's1', start: 0, end: 2, text: '开头提出主要观点。' }, { id: 's2', start: 90, end: 95, text: '末尾强调适用条件。' }] };
export const summary = { overview: '全视频概述', key_points: [{ title: '主要观点', detail: '开头与结尾综合整理。', segment_ids: ['s1', 's2'] }], chapters: [], steps: [], caveats: [] };
export const chat = text => ({ choices: [{ message: { content: text }, finish_reason: 'stop' }] });
