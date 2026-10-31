#!/usr/bin/env python3
"""Package only the independently buildable Free tree after successful tests."""
from pathlib import Path
import hashlib,json,shutil,zipfile,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parent.parent
VERSION='2.2.0';IDS={'pikachu','charmander','bulbasaur','squirtle','eevee','magikarp','slowpoke','pidgey','psyduck','vulpix'}
FORBIDDEN={'ProgressionConfig','ProgressSql','Evolution','DuelState','DuelLobby','DuelEngine','DuelCommands','DuelReturns','Arena','ArenaBlueprint','ArenaProtection','ArenaStore','ArenaService','Trainer','TrainerAi','TrainerOffers','TrainerStore','TrainerEngine','TrainerCommands','TrainerNpc','TradeEngine','ProgressionService','ProgressMenus','CenterEngine','CenterStore','CenterBlueprint','CenterCommands','Clinic','DexInfo','FollowPath','CompanionRules','CitizensHook','CitizensAccess'}
def sources():
 roots=['src','gradle','scripts'];files=[]
 for folder in roots:files.extend(p for p in (ROOT/folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
 files.extend(ROOT/n for n in ['build.gradle','settings.gradle','gradlew','gradlew.bat','.gitignore','README.md','README.ru.md','LICENSE','THIRD_PARTY.md'])
 return sorted(files)
def audit():
 for path in sources():
  relative=path.relative_to(ROOT);assert not path.is_symlink(),relative;assert 'upgrades' not in relative.parts,relative;assert path.stem not in FORBIDDEN,relative;assert path.suffix not in {'.db','.zip'},relative
  if path.suffix=='.jar':assert relative.as_posix()=='gradle/wrapper/gradle-wrapper.jar'
 for name in ['species.yml','spawns.yml']:assert set(json.loads((ROOT/'src/main/resources'/name).read_text()))==IDS
 moves=json.loads((ROOT/'src/main/resources/moves.yml').read_text());assert len(moves)==40 and all(m.rsplit('_',1)[0] in IDS for m in moves)
 assert set(json.loads((ROOT/'src/main/resources/balls.yml').read_text()))=={'poke'}
 for lang in ['en','ru']:
  m=json.loads((ROOT/f'src/main/resources/messages_{lang}.yml').read_text());assert set(m['species'])==IDS and len(m['moves'])==40;assert not {'pvp','trade','trainer','progress','names','center','dex-info'}.intersection(m)
 for folder in ['models','animations']:assert {p.stem for p in (ROOT/'src/main/resources'/folder).glob('*.yml')}==IDS
 return sources()
def main():
 files=audit();reports=[ET.parse(p).getroot() for p in (ROOT/'build/test-results/test').glob('TEST-*.xml')];assert reports and all(int(r.attrib['failures'])+int(r.attrib['errors'])+int(r.attrib.get('skipped',0))==0 for r in reports),'Run ./gradlew test jar first'
 jar=ROOT/f'build/libs/Pokemonika-Free-{VERSION}.jar';assert jar.exists()
 with zipfile.ZipFile(jar) as archive:
  assert not any(n.startswith('net/citizensnpcs/') or any('/'+c+'.class' in n for c in FORBIDDEN) or n.startswith('upgrades/') for n in archive.namelist())
  for path in (ROOT/'src/main/resources').rglob('*.yml'):assert archive.read(path.relative_to(ROOT/'src/main/resources').as_posix())==path.read_bytes()
  for path in (ROOT/'build/classes/java/main').rglob('*.class'):assert archive.read(path.relative_to(ROOT/'build/classes/java/main').as_posix())==path.read_bytes()
 dist=ROOT/'dist';dist.mkdir(exist_ok=True);shutil.copy2(jar,dist/jar.name)
 with zipfile.ZipFile(dist/f'Pokemonika-Free-{VERSION}-sources.zip','w',zipfile.ZIP_DEFLATED) as archive:
  for path in files:archive.write(path,'Pokemonika-Free/'+path.relative_to(ROOT).as_posix())
 with zipfile.ZipFile(dist/f'Pokemonika-Free-{VERSION}.zip','w',zipfile.ZIP_DEFLATED) as archive:
  archive.write(jar,jar.name)
  for name in ['README.md','README.ru.md','LICENSE','THIRD_PARTY.md']:archive.write(ROOT/name,name)
 info={'edition':'free','version':VERSION,'species':10,'signatureMoves':40,'balls':1,'partySlots':6,'storageSlots':45,'defaultLanguage':'en','languages':['en','ru'],'testsPassed':sum(int(r.attrib['tests']) for r in reports),'serverValidation':'not-run-user-request','publicSourceFiles':len(files),'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest()}
 (dist/'build-info.json').write_text(json.dumps(info,indent=2)+'\n');(dist/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n' for p in sorted(dist.iterdir()) if p.suffix in {'.jar','.zip'}))
 (dist/'public-files.txt').write_text(''.join(p.relative_to(ROOT).as_posix()+'\n' for p in files));print(json.dumps(info))
if __name__=='__main__':main()
