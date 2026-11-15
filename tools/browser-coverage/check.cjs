const fs = require('node:fs');
const path = require('node:path');
const convert = require('v8-to-istanbul');
const {createCoverageMap} = require('istanbul-lib-coverage');
(async () => {
  const combined = createCoverageMap({});
  const measured = ['migrations.js', 'theme.js'];
  const actual = fs.readdirSync('src/main/resources/static').filter(name => name.endsWith('.js')).sort();
  if (JSON.stringify(actual) !== JSON.stringify(measured)) throw Error('Update the browser coverage scope for all production scripts');
  for (const [script, evidence] of [['migrations', 'migration'], ['theme', 'theme']]) {
    const filename = path.resolve(`src/main/resources/static/${script}.js`);
    const raw = JSON.parse(fs.readFileSync(`target/${evidence}-browser-v8.json`, 'utf8'));
    const samples = Array.isArray(raw) ? raw : [raw];
    if (!samples.length) throw Error('No browser samples for ' + script);
    for (const sample of samples) {
      if (sample.source !== fs.readFileSync(filename, 'utf8')) throw Error('Browser coverage is stale: ' + script);
      const coverage = convert(filename, 0, {source: sample.source});
      await coverage.load();
      coverage.applyCoverage(sample.functions);
      combined.merge(coverage.toIstanbul());
    }
  }
  const report = combined.toJSON();
  fs.writeFileSync('target/browser-coverage.json', JSON.stringify(report));
  for (const [filename, result] of Object.entries(report)) {
    for (const [name, counts] of Object.entries({lines: Object.values(result.s), branches: Object.values(result.b).flat(), functions: Object.values(result.f)})) {
      if (counts.length === 0) throw Error('No browser ' + name + ' measured');
      const covered = counts.filter(count => count > 0).length;
      console.log(`${path.basename(filename)} ${name}: ${covered}/${counts.length}`);
      if (covered !== counts.length) throw Error('Incomplete browser ' + name + '; inspect target/browser-coverage.json');
    }
  }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
