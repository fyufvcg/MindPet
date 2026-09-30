"""Generate paired memory ablation timelines without model calls."""
from __future__ import annotations
import argparse,hashlib,json
from collections import Counter
from datetime import datetime,timedelta,timezone
from pathlib import Path
from generate_synthetic_curator_dataset import CITIES,JOBS,PROJECTS
VERSION='memory-curator-ablation-v4'
def build(index,profile,split):
    tid=f'timeline-{index:03d}'
    home,old,city,possible,middle=[CITIES[(index+d)%len(CITIES)] for d in (0,4,8,12,16)]
    job,oldjob=JOBS[index%len(JOBS)],JOBS[(index+3)%len(JOBS)]
    project=PROJECTS[index%len(PROJECTS)];pref='先说结论，再列简洁步骤';style='使用分点表达';course=f'第{index}期数据整理课程'
    core={'home':('home_location',home,'stable','observed'),'old':('current_location',old,'historical','observed'),
        'city_initial':('current_location',city,'current','observed'),'city_middle':('current_location',middle,'current','observed'),
        'city':('current_location',city,'current','observed'),'oldjob':('occupation_current',oldjob,'historical','observed'),
        'job':('occupation_current',job,'current','observed'),'project':('current_project',project,'current','observed'),
        'pref':('preference',pref,'stable','observed'),'style':('preference',style,'stable','observed'),
        'possible':('current_location',possible,'planned','possible'),'course_planned':('plan',course,'planned','planned'),
        'course_final':('plan',course,'planned' if index%2 else 'episodic','negated' if index%2 else 'observed')}
    forms={'home':[f'我的老家在{home}。',f'我长期的家在{home}。',f'我的家乡是{home}。',f'长期住址是{home}。'],
        'old':[f'搬家前我住在{old}。'],'city_initial':[f'我现在住在{city}。'],'city_middle':[f'我已经搬到{middle}。'],
        'city':[f'我现在重新住在{city}。',f'我的现居城市是{city}。',f'我目前住在{city}。',f'目前我一直住在{city}。'],
        'oldjob':[f'我以前从事{oldjob}。'],
        'job':[f'我的当前工作岗位是{job}。',f'我目前担任{job}。',f'我现在的职业是{job}。',f'我目前从事{job}。',f'我的现职是{job}。'],
        'project':[f'我现在负责{project}。',f'我目前参与{project}。',f'我正在负责{project}。',f'我继续负责{project}。',f'当前项目是{project}。'],
        'pref':[f'我希望回复{pref}。','之后回答请先给结论，再列简洁步骤。','我比较习惯先看结论，再看简洁步骤。',f'回复形式我偏好{pref}。',f'我仍偏好{pref}。'],
        'style':[f'我希望回复{style}。',f'我的沟通偏好是{style}。',f'回复形式我偏好{style}。'],
        'possible':[f'我以后可能搬去{possible}。',f'未来搬去{possible}仍只是备选计划。'],
        'course_planned':[f'我计划参加{course}。'],'course_final':[f'我已经取消{course}。' if index%2 else f'我已经完成{course}。']}
    slots={1:['home','pref'],2:['old','oldjob'],3:['possible'],4:['project'],5:['job'],7:['style'],10:['city_initial'],
        11:['course_planned'],14:['pref'],15:['project'],18:['city_middle'],20:['possible'],23:['home'],26:['city','job'],
        28:['course_final'],32:['style'],35:['city'],38:['job'],40:['city','job','home']}
    if profile=='redundant':
        for n in (8,9,12,13,16,19,21,24,25,27,30,31,33,36,37):
            available=['home','pref','style','project','job']+(['city'] if n>=26 else [])
            slots[n]=[available[(n+index)%len(available)]]
    noise={6:'刚才的临时计算已经完成。',17:'这一页先核对页码。',22:'今天的临时查找任务结束。',29:'一次性提醒已处理。',34:'这次文件排序已经完成。',39:'刚才的操作不用再提醒。'}
    base=datetime(2026,3,1,tzinfo=timezone(timedelta(hours=8)))+timedelta(days=index)
    usage=Counter();sources={k:[] for k in core};turns=[]
    for n in range(1,41):
        keys=slots.get(n,[])
        if keys:
            chunks=[]
            for key in keys:
                form=forms[key][usage[key]%len(forms[key])]
                if usage[key]>=len(forms[key]):form='再次确认，'+form
                usage[key]+=1;chunks.append(form);sources[key].append(n)
            message=''.join(chunks)
        elif n in noise:message=noise[n]
        else:
            key=f'experience{n}';value=f'2025年完成第{n}期培训'
            if n in (19,30):value+=f'，培训地点是{CITIES[(index+n)%len(CITIES)]}，参加人数是{n+8}人'
            core[key]=('experience',value,'episodic','observed');sources[key]=[n];keys=[key];message=f'我曾经{value}。'
        if n==13:message+=f'我的朋友住在{CITIES[(index+2)%len(CITIES)]}，这不是我的住址。'
        at=(base+timedelta(days=(n-1)//8*14,minutes=n)).astimezone(timezone.utc).isoformat().replace('+00:00','Z')
        turns.append({'turn_id':f'{tid}-turn-{n:02d}','session_id':f'{tid}-session-{(n-1)//8+1}','user':message,'assistant':'',
            'occurred_at':at,'timezone':'Asia/Shanghai','semantic_cluster_id':[f'{tid}-{k}' for k in keys],
            'source_category':'durable' if keys else 'transient_noise','compressible_source':bool(keys) and n!=13,
            'irreducible_reason':'other_subject' if n==13 else ''})
    facts=[]
    for key,(predicate,value,scope,assertion) in core.items():
        facts.append({'gold_fact_id':f'{tid}-fact-{key}','predicate':predicate,'normalized_value':value,'scope':scope,'assertion':assertion,
            'valid_from':'','valid_to':'','time_status':'unresolved','should_store':True,'should_be_current':key in ('home','city','job','project'),
            'interval_id':key if key.startswith('city') else '', 'source_turn_ids':[f'{tid}-turn-{n:02d}' for n in sources[key]],
            'evidence':[turns[n-1]['user'] for n in sources[key]],'duplicate_cluster_id':f'{tid}-{key}','raw_time_expression':'','sensitive':False,'importance':0.9})
    specs=[('current_state','现在住在哪座城市？',['city'],[city],[old,middle,possible],'current'),
        ('current_state','现在从事什么工作？',['job'],[job],[oldjob],'current'),('current_state','目前负责哪个项目？',['project'],[project],[],'current'),
        ('preference','我喜欢怎样安排回答顺序？',['pref'],['结论','步骤'],[],'stable'),('preference','我偏好的回答排版是什么？',['style'],['分点'],[],'stable'),
        ('historical','搬家前住在哪座城市？',['old'],[old],[],'historical'),
        ('plan_vs_reality','未来可能搬去哪里，这是已发生还是备选？',['possible'],[possible],[],'planned_possible'),
        ('cancelled_or_completed','此前报名的课程现在取消了还是完成了？',['course_final'],[course,'取消' if index%2 else '完成'],[],'cancelled' if index%2 else 'completed'),
        ('multi_fact','现居城市和现任职位分别是什么？',['city','job'],[city,job],[middle,oldjob],'current'),
        ('multi_fact','长期住址和当前负责项目分别是什么？',['home','project'],[home,project],[],'current'),
        ('unsupported_abstention','我的护照号码是多少？',[],[],[],'unknown'),('unsupported_abstention','我的银行卡余额是多少？',[],[],[],'unknown')]
    queries=[]
    for qi,(kind,question,keys,answers,forbidden,state) in enumerate(specs,1):
        queries.append({'query_id':f'{tid}-q{qi:02d}','query_type':kind,'question':question,'relevant_gold_fact_ids':[f'{tid}-fact-{k}' for k in keys],
            'expected_answer':'；'.join(answers),'answer_contains_all':answers,'answer_contains_any':answers[:1],
            'forbidden_answer_contains_any':forbidden,'expected_state':state,'should_abstain':not keys,'answer_evaluation':True})
    seen=set();repeated=0
    for turn in turns:
        clusters=set(turn['semantic_cluster_id']);repeated+=bool(clusters&seen);seen.update(clusters)
    exact=len(turns)-len(set(t['user'] for t in turns))
    return {'dataset_version':VERSION,'synthetic':True,'timeline_id':tid,'user_id':f'synthetic-{tid}','split':split,'sample_profile':profile,
        'paired_skeleton_id':index,'primary_scenario':'memory_ablation','language_challenge':True,'timezone':'Asia/Shanghai','as_of':'2026-09-30T23:59:00+08:00',
        'turns':turns,'facts':facts,'queries':queries,'expected_profile':{'home_location':home,'current_location':city,'occupation_current':job,'current_project':project},
        'notes':{'independent_fact_count':len(facts),'repeated_input_rate':repeated/40,'exact_duplicate_input_rate':exact/40,
            'comparison_scope':'Matched core facts, transitions and questions; normal has additional independent experiences.',
            'other_subject_detail':'Friend address at turn 13 is outside user fact ontology and must remain in raw/residual corpus.'}}
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output-dir',type=Path,default=Path(__file__).parent/'data/ablation-v4');args=parser.parse_args();args.output_dir.mkdir(parents=True,exist_ok=True)
    for profile in ('normal','redundant'):
        rows=[build(i,profile,'development' if i<=4 else 'locked_test') for i in range(1,21)]
        for row in rows:
            assert len(row['turns'])==40 and len(row['queries'])==12
            assert all(f['source_turn_ids'] for f in row['facts'])
            assert row['notes']['exact_duplicate_input_rate']<=0.1
        path=args.output_dir/f'{profile}.jsonl';path.write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows),encoding='utf-8')
        manifest={'version':VERSION,'profile':profile,'development_timelines':4,'formal_timelines':16,'formal_inputs':640,'formal_questions':192,
            'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'seed':20260930,'sample_notes':[r['notes'] for r in rows]}
        path.with_suffix('.manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps({k:v for k,v in manifest.items() if k!='sample_notes'},ensure_ascii=False))
if __name__=='__main__':main()
