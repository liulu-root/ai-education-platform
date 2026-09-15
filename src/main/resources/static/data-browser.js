const dataBrowser = { version: 0, page: 1, total: 0, rows: [], columns: [], search: '', busy: false };
const dataElement = id => document.getElementById(`data-${id}`);

function resetDataBrowser() {
    dataBrowser.version++;
    Object.assign(dataBrowser, { page: 1, total: 0, rows: [], columns: [], search: '', busy: false });
    dataElement('table').replaceChildren();
    dataElement('columns').replaceChildren();
    dataElement('rows').replaceChildren();
    dataElement('detail-fields').replaceChildren();
    dataElement('detail').close();
    dataElement('search').value = '';
    dataElement('size').value = '25';
    dataElement('status').textContent = '';
    setDataBusy(false);
}

function setDataBusy(busy) {
    dataBrowser.busy = busy;
    dataElement('grid').setAttribute('aria-busy', String(busy));
    dataElement('table').disabled = busy || !dataElement('table').options.length;
    dataElement('size').disabled = busy;
    dataElement('refresh').disabled = busy;
    dataElement('search-form').querySelector('button[type="submit"]').disabled = busy;
    dataElement('prev').disabled = busy || dataBrowser.page <= 1;
    dataElement('next').disabled = busy || dataBrowser.page * Number(dataElement('size').value) >= dataBrowser.total;
}

async function loadDataBrowser() {
    const version = ++dataBrowser.version;
    const selected = dataElement('table').value;
    setDataBusy(true);
    dataElement('status').textContent = '正在加载数据表…';
    try {
        const tables = await api('/api/data/tables');
        if (version !== dataBrowser.version) return;
        dataElement('table').replaceChildren(...tables.map(table => {
            const option = document.createElement('option');
            option.value = table.name;
            option.textContent = `${table.label} · ${table.total} 条`;
            return option;
        }));
        if (tables.some(table => table.name === selected)) dataElement('table').value = selected;
        if (!tables.length) {
            dataElement('status').textContent = '当前账号暂无可查看的数据表';
            setDataBusy(false);
            return;
        }
        await loadDataRows();
    } catch (error) {
        if (version !== dataBrowser.version) return;
        showDataError(error);
    }
}

async function loadDataRows() {
    if (!dataElement('table').value) return;
    const version = ++dataBrowser.version;
    setDataBusy(true);
    dataElement('status').textContent = '正在读取记录…';
    dataElement('rows').replaceChildren();
    dataElement('detail').close();
    const query = new URLSearchParams({ page: dataBrowser.page, size: dataElement('size').value, search: dataBrowser.search });
    try {
        const result = await api(`/api/data/tables/${encodeURIComponent(dataElement('table').value)}?${query}`);
        if (version !== dataBrowser.version) return;
        Object.assign(dataBrowser, { page: result.page, total: result.total, rows: result.rows, columns: result.columns });
        dataElement('table-title').textContent = result.label;
        dataElement('table-name').textContent = result.name;
        const headings = document.createElement('tr');
        for (const name of ['记录', ...result.columns]) {
            const th = document.createElement('th');
            th.scope = 'col';
            th.textContent = name;
            headings.append(th);
        }
        dataElement('columns').replaceChildren(headings);
        const rows = result.rows.map((row, index) => {
            const tr = document.createElement('tr');
            const action = document.createElement('td');
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'text-button';
            button.textContent = '详情';
            button.setAttribute('aria-label', `查看第 ${(result.page - 1) * result.size + index + 1} 条记录详情`);
            button.addEventListener('click', () => showDataDetail(row));
            action.append(button);
            tr.append(action);
            for (const column of result.columns) {
                const cell = document.createElement('td');
                const value = dataValue(row[column]);
                cell.textContent = value.length > 100 ? `${value.slice(0, 100)}…` : value;
                if (row[column] == null) cell.className = 'data-null';
                tr.append(cell);
            }
            return tr;
        });
        dataElement('rows').replaceChildren(...rows);
        dataElement('status').textContent = result.total
            ? `共 ${result.total.toLocaleString('zh-CN')} 条${dataBrowser.search ? '匹配记录' : '记录'} · 更新于 ${new Date().toLocaleTimeString('zh-CN')}`
            : dataBrowser.search ? '没有匹配的记录' : '此表暂无记录';
        dataElement('page-info').textContent = `第 ${result.page} / ${Math.max(1, Math.ceil(result.total / result.size))} 页`;
        setDataBusy(false);
    } catch (error) {
        if (version !== dataBrowser.version) return;
        showDataError(error);
    }
}

function showDataError(error) {
    dataBrowser.rows = [];
    dataBrowser.total = 0;
    dataElement('columns').replaceChildren();
    dataElement('rows').replaceChildren();
    dataElement('status').textContent = `加载失败：${error.message}`;
    dataElement('page-info').textContent = '加载失败';
    setDataBusy(false);
    dataElement('prev').disabled = true;
}

function dataValue(value) {
    if (value == null) return 'NULL';
    if (typeof value === 'object') return JSON.stringify(value, null, 2);
    return String(value);
}

function showDataDetail(row) {
    const fields = dataBrowser.columns.map(column => {
        const group = document.createElement('div');
        const term = document.createElement('dt');
        term.textContent = column;
        const definition = document.createElement('dd');
        let value = dataValue(row[column]);
        if (typeof row[column] === 'string' && /^[\[{]/.test(value.trim())) {
            try { value = JSON.stringify(JSON.parse(value), null, 2); } catch { /* Keep non-JSON text intact. */ }
        }
        definition.textContent = value;
        group.append(term, definition);
        return group;
    });
    dataElement('detail-fields').replaceChildren(...fields);
    dataElement('detail').showModal();
}

dataElement('search-form').addEventListener('submit', event => {
    event.preventDefault();
    if (dataBrowser.busy) return;
    dataBrowser.page = 1;
    dataBrowser.search = dataElement('search').value.trim();
    loadDataRows();
});
dataElement('table').addEventListener('change', () => {
    dataBrowser.page = 1;
    dataBrowser.search = '';
    dataElement('search').value = '';
    loadDataRows();
});
dataElement('size').addEventListener('change', () => { dataBrowser.page = 1; loadDataRows(); });
dataElement('refresh').addEventListener('click', () => loadDataBrowser());
dataElement('prev').addEventListener('click', () => { dataBrowser.page--; loadDataRows(); });
dataElement('next').addEventListener('click', () => { dataBrowser.page++; loadDataRows(); });
dataElement('close').addEventListener('click', () => dataElement('detail').close());
