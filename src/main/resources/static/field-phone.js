
const form = document.querySelector('#visitForm');
const parts = document.querySelector('#parts');
document.querySelector('#add').onclick = () => {
  const fieldset = document.querySelector('#part').content.cloneNode(true);
  fieldset.querySelector('button').onclick = event => event.target.closest('fieldset').remove();
  parts.append(fieldset);
};
form.onsubmit = event => {
  event.preventDefault();
  const fields = new FormData(form);
  const rows = [['minutes', fields.get('minutes')], ['evidence_note', fields.get('note')]];
  for (const fieldset of parts.children) {
    rows.push(['part', ...Array.from(fieldset.querySelectorAll('input'), input => input.value)]);
  }
  const quote = value => '"' + value.replaceAll('"', '""') + '"';
  const csv = document.querySelector('#packet').value.trimEnd() + '\r\n' +
    rows.map(row => row.map(quote).join(',')).join('\r\n') + '\r\n';
  const url = URL.createObjectURL(new Blob([csv], {type:'text/csv;charset=utf-8'}));
  const link = document.createElement('a');
  link.href = url;
  link.download = 'completed-visit.csv';
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
};
