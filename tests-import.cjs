const fs = require('fs'); const vm = require('vm'); const assert = require('assert');
const sandbox = { window: {}, console }; vm.createContext(sandbox);
vm.runInContext(fs.readFileSync('app/src/main/assets/www/question-import.js','utf8'), sandbox);
const parse = sandbox.window.QuizStudioImport.parseText;
const samples = [
  ['pipe', 'Capital of France? | Paris | Rome | Madrid | Berlin | A', 1],
  ['tab', 'Largest planet?\tEarth\tMars\tJupiter\tVenus\tC', 1],
  ['csv', 'Question,A,B,C,D,Correct\n"Capital, France?",Paris,Rome,Madrid,Berlin,A', 1],
  ['markdown', '| Question | A | B | C | D | Correct |\n|---|---|---|---|---|---|\n| 2+2? | 3 | 4 | 5 | 6 | B |', 1],
  ['json', JSON.stringify([{question:'Sky color?',options:['Blue','Green','Red','Black'],correct:'A'}]), 1],
  ['bad', 'Q | A | B | C | D | Z', 0]
];
for (const [name,input,expected] of samples) { const out = parse(input, name==='json'?'json':'text'); if(name==='bad') assert.equal(out.errors.length,1); else assert.equal(out.questions.length,expected, name+': '+JSON.stringify(out)); }
const dupe = parse('Q? | A | B | C | D | A\nQ? | A | B | C | D | A'); assert.equal(dupe.questions.length,1); assert.equal(dupe.duplicates,1);
console.log('PASS: pipe, tab, CSV quoting, Markdown table, JSON, invalid row and duplicate detection');
