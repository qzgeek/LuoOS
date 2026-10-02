/*
检查 LuoOS 语言文件（扁平 JSON）之间的键一致性。
原始 TOML 版本来自 BlueMap 项目（MIT），但 LuoOS 的语言文件是扁平 JSON，
解析方式完全不同，因此这里按 JSON 重写，保留“以 en_us 为基准比较”的语义。
*/

import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";

const langFolder = "../../folia/src/main/resources/data/heos/lang";
const sourceLanguageName = "en_us";

function load(file) {
    const raw = readFileSync(path.join(langFolder, file), "utf8");
    let data;
    try {
        data = JSON.parse(raw);
    } catch (e) {
        throw new Error(`语言文件不是合法 JSON：${file} — ${e.message}`);
    }
    if (data === null || typeof data !== "object" || Array.isArray(data)) {
        throw new Error(`语言文件必须是键值对象：${file}`);
    }
    return new Set(Object.keys(data));
}

const languageFiles = readdirSync(langFolder).filter((f) => f.endsWith(".json"));
if (!languageFiles.length) throw new Error(`未找到语言文件：${langFolder}`);

const sourceFile = `${sourceLanguageName}.json`;
if (!languageFiles.includes(sourceFile)) {
    throw new Error(`缺少基准语言文件 ${sourceFile}`);
}
const sourceKeys = load(sourceFile);

let failed = false;
const upToDate = [];
for (const file of languageFiles) {
    if (file === sourceFile) continue;
    const name = file.replace(/\.json$/, "");
    const keys = load(file);
    const missing = [...sourceKeys].filter((k) => !keys.has(k));
    const extra = [...keys].filter((k) => !sourceKeys.has(k));
    if (!missing.length && !extra.length) {
        upToDate.push(name);
        continue;
    }
    failed = true;
    console.log(`=== ${name} ===`);
    if (missing.length) {
        console.log(`Missing (${missing.length}):`);
        for (const k of missing) console.log("-", k);
        console.log();
    }
    if (extra.length) {
        console.log(`Extra (${extra.length}):`);
        for (const k of extra) console.log("-", k);
        console.log();
    }
}

if (upToDate.length) console.log("Up to date:", upToDate.join(", "));
if (failed) {
    console.error("语言文件键不一致，请补齐后再提交。");
    process.exit(1);
}
console.log(`检查通过：${languageFiles.length} 个语言文件，基准 ${sourceFile} 共 ${sourceKeys.size} 个键。`);
