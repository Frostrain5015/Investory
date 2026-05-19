/* ── 盈亏鉴 / Investory — ECharts initializers ─────────────────────────── */

/* ─── Dashboard: allocation pie + P&L rank bar + cumulative line ─────────── */
function initDashboardCharts(portfolioId, ctx) {
    if (!portfolioId) return;

    // 1. Allocation pie
    fetchJson(ctx + '/api/chart?type=allocation&portfolioId=' + portfolioId)
        .then(data => {
            if (!data || !data.length) return;
            const chart = echarts.init(document.getElementById('chart-allocation'));
            chart.setOption({
                tooltip: { trigger: 'item', formatter: '{b}: {d}%' },
                legend: { orient: 'vertical', right: 10, top: 'center', textStyle: { fontSize: 12 } },
                series: [{
                    type: 'pie', radius: ['40%', '70%'],
                    center: ['40%', '50%'],
                    data: data.map(d => ({ name: d.name, value: parseFloat(d.value) })),
                    label: { show: false },
                    emphasis: { label: { show: true, fontSize: 13, fontWeight: 'bold' } }
                }]
            });
            window.addEventListener('resize', () => chart.resize());
        });

    // 2. P&L rank horizontal bar
    fetchJson(ctx + '/api/chart?type=pnl_rank&portfolioId=' + portfolioId)
        .then(data => {
            if (!data || !data.length) return;
            const chart = echarts.init(document.getElementById('chart-pnl-rank'));
            const names = data.map(d => d.name);
            const values = data.map(d => parseFloat(d.pnl));
            const colors = values.map(v => v >= 0 ? '#ef5350' : '#26a69a');
            chart.setOption({
                tooltip: {
                    trigger: 'axis',
                    formatter: p => `${p[0].name}<br>浮盈: ${p[0].value.toFixed(2)}<br>收益率: ${data[p[0].dataIndex].pnlPct}%`
                },
                grid: { left: 90, right: 20, top: 10, bottom: 30 },
                xAxis: { type: 'value', axisLabel: { formatter: v => v.toFixed(0) } },
                yAxis: { type: 'category', data: names, axisLabel: { fontSize: 12 } },
                series: [{
                    type: 'bar', data: values,
                    itemStyle: { color: p => colors[p.dataIndex] },
                    label: { show: true, position: 'right', formatter: p => p.value.toFixed(0) }
                }]
            });
            window.addEventListener('resize', () => chart.resize());
        });

    // 3. Cumulative return line
    fetchJson(ctx + '/api/chart?type=cumulative_return&portfolioId=' + portfolioId + '&days=365')
        .then(data => {
            if (!data || !data.length) return;
            const chart = echarts.init(document.getElementById('chart-cumulative'));
            chart.setOption({
                tooltip: { trigger: 'axis' },
                grid: { left: 50, right: 20, top: 20, bottom: 30 },
                xAxis: { type: 'category', data: data.map(d => d.date), axisLabel: { rotate: 30, fontSize: 11 } },
                yAxis: { type: 'value', axisLabel: { formatter: v => v.toFixed(1) + '%' } },
                series: [{
                    name: '累计收益率', type: 'line', smooth: true,
                    data: data.map(d => parseFloat(d.return).toFixed(2)),
                    areaStyle: { opacity: .15 },
                    lineStyle: { width: 2 },
                    itemStyle: { color: '#4caf50' },
                    markLine: { data: [{ yAxis: 0, lineStyle: { color: '#999', type: 'dashed' } }] }
                }]
            });
            window.addEventListener('resize', () => chart.resize());
        });
}

/* ─── Stock detail: price line with cost overlay ─────────────────────────── */
function initPriceChart(domId, symbol, days, avgCost, dilCost, ctx) {
    fetchJson(ctx + '/api/chart?type=price&symbol=' + encodeURIComponent(symbol) + '&days=' + days)
        .then(data => {
            if (!data || !data.length) {
                document.getElementById(domId).innerHTML =
                    '<p class="text-center text-muted pt-5">暂无价格数据，等待爬虫同步…</p>';
                return;
            }
            const chart = echarts.init(document.getElementById(domId));
            const dates   = data.map(d => d.date);
            const closes  = data.map(d => parseFloat(d.close));
            const opens   = data.map(d => parseFloat(d.open));
            const highs   = data.map(d => parseFloat(d.high));
            const lows    = data.map(d => parseFloat(d.low));
            const volumes = data.map(d => d.volume);

            const series = [
                {
                    name: '价格', type: 'candlestick',
                    data: dates.map((_, i) => [opens[i], closes[i], lows[i], highs[i]]),
                    itemStyle: { color: '#ef5350', color0: '#26a69a', borderColor: '#ef5350', borderColor0: '#26a69a' }
                },
                {
                    name: '成交量', type: 'bar', xAxisIndex: 1, yAxisIndex: 1,
                    data: volumes,
                    itemStyle: { color: '#90a4ae', opacity: .6 }
                }
            ];

            if (avgCost) {
                series.push({
                    name: '平均成本', type: 'line',
                    data: dates.map(() => parseFloat(avgCost)),
                    symbol: 'none', lineStyle: { color: '#ff9800', type: 'dashed', width: 1.5 }
                });
            }
            if (dilCost && dilCost !== avgCost) {
                series.push({
                    name: '摊薄成本', type: 'line',
                    data: dates.map(() => parseFloat(dilCost)),
                    symbol: 'none', lineStyle: { color: '#29b6f6', type: 'dashed', width: 1.5 }
                });
            }

            chart.setOption({
                legend: { top: 5 },
                tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
                grid: [
                    { left: 60, right: 20, top: 40, bottom: 100 },
                    { left: 60, right: 20, height: 50, bottom: 30 }
                ],
                xAxis: [
                    { type: 'category', data: dates, axisLabel: { rotate: 30, fontSize: 11 } },
                    { type: 'category', gridIndex: 1, data: dates, axisLabel: { show: false } }
                ],
                yAxis: [
                    { type: 'value', scale: true, splitLine: { lineStyle: { type: 'dashed' } } },
                    { gridIndex: 1, type: 'value', scale: true, splitLine: { show: false }, axisLabel: { show: false } }
                ],
                dataZoom: [
                    { type: 'inside', start: 60, end: 100, xAxisIndex: [0, 1] },
                    { type: 'slider',  start: 60, end: 100, xAxisIndex: [0, 1], bottom: 5 }
                ],
                series
            });
            window.addEventListener('resize', () => chart.resize());
        });
}

/* ─── P&L calendar heatmap ───────────────────────────────────────────────── */
function initPnlCalendar(domId, year, ctx) {
    fetchJson(ctx + '/api/chart?type=pnl_calendar&portfolioId=&year=' + year)
        .then(raw => {
            const chart = echarts.init(document.getElementById(domId));
            // raw = [[date, pnl], ...]
            const data = (raw || []).map(r => [r[0], parseFloat(r[1])]);
            const maxAbs = data.reduce((m, r) => Math.max(m, Math.abs(r[1])), 1);

            chart.setOption({
                tooltip: {
                    formatter: p => `${p.data[0]}<br>盈亏: ${p.data[1] >= 0 ? '+' : ''}${p.data[1].toFixed(2)}`
                },
                visualMap: {
                    min: -maxAbs, max: maxAbs,
                    calculable: true,
                    orient: 'horizontal',
                    left: 'center', bottom: 20,
                    inRange: { color: ['#26a69a', '#ffffff', '#ef5350'] },
                    text: ['盈利', '亏损']
                },
                calendar: {
                    top: 60, left: 40, right: 40,
                    range: String(year),
                    cellSize: ['auto', 18],
                    splitLine: { lineStyle: { color: '#e0e0e0' } },
                    dayLabel: { nameMap: 'ZH' },
                    monthLabel: { nameMap: 'ZH' }
                },
                series: [{
                    type: 'heatmap',
                    coordinateSystem: 'calendar',
                    data: data
                }]
            });
            window.addEventListener('resize', () => chart.resize());
        });
}

/* ─── Shared fetch helper ─────────────────────────────────────────────────── */
function fetchJson(url) {
    return fetch(url, { credentials: 'same-origin' })
        .then(r => r.ok ? r.json() : [])
        .catch(() => []);
}
