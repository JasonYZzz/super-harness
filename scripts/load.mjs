// Run only inside acceptance.mjs's disposable database. Each stream checks its entire persisted sequence.
import {execFileSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
import {setTimeout as sleep} from 'node:timers/promises';
if (process.env.SH_ACCEPTANCE_LOAD!=='isolated') throw new Error('Run scripts/acceptance.mjs --load');
const token=execFileSync('scripts/dev-token.sh',{encoding:'utf8'}).trim();
const base=process.env.SH_API_URL+'/api/v1', count=100, copies=5, submitted=[];
async function request(path,body,headers={}) {
  const result=await fetch(base+path,{method:body?'POST':'GET',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json',...headers},
    body:body&&JSON.stringify(body),signal:AbortSignal.timeout(180000)});
  if(!result.ok) throw new Error(path+' '+result.status+' '+await result.text());
  return result;
}
const agent=(await (await request('/agents',{name:'load'})).json()).agentId;
const revision=(await (await request('/agents/'+agent+'/revisions',{model:'local-echo',prompt:'test',outputMode:'APPROVED_ARTIFACT'})).json()).agentRevisionId;
const started=performance.now();
const runs=await Promise.all(Array.from({length:count},async(_,i)=>{
  const conv=await (await request('/conversations',{agentId:agent,agentRevisionId:revision})).json();
  const begin=performance.now();
  const result=await (await request('/runs',{conversationId:conv.conversationId,input:{type:'text',text:'load '+i}},{'Idempotency-Key':randomUUID()})).json();
  submitted.push(performance.now()-begin);
  return result.runId;
}));
let connected=0;
async function read(run) {
  const stream=await request('/runs/'+run+'/events');
  connected++;
  const data=await stream.text();
  const events=data.split('\n').filter(s=>s.startsWith('data: ')).map(s=>JSON.parse(s.slice(6)));
  assert.equal(events.at(-1).type,'run.completed');
  assert.ok(events.every((e,i)=>e.seq===i+1 && e.runId===run));
}
const streams=Promise.all(runs.flatMap(r=>Array.from({length:copies},()=>read(r))));
const approve=async()=>{
  const pending=new Set(runs), timeout=Date.now()+150000;
  while(pending.size && Date.now()<timeout) {
    if(connected===count*copies) {
      const approvals=await (await request('/approvals')).json();
      await Promise.all(approvals.filter(a=>pending.has(a.runId)).map(async a=>{
        await request('/approvals/'+a.id+'/decision',{approve:true,argsHash:a.argsHash});pending.delete(a.runId);
      }));
    }
    await sleep(250);
  }
  assert.equal(pending.size,0);
};
await Promise.all([approve(),streams]);
console.log(JSON.stringify({conversations:count,peakOpenStreams:connected,completedStreams:count*copies,
  admissionP95Ms:Math.round(submitted.sort((a,b)=>a-b)[Math.ceil(count*.95)-1]),elapsedSeconds:Math.round((performance.now()-started)/1000)}));
