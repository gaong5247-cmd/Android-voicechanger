#!/usr/bin/env python3
"""Build-time experiment, NOT a complete MeanVC2 streaming mobile exporter.
Exports independently testable ASR / GTM components and records CPU parity.
Does not label any bundle Android-ready without the unimplemented components.
"""
import argparse,json,pathlib,sys,hashlib
import numpy as np
import torch
import onnx
import onnxruntime as ort
from safetensors.torch import load_file

def check_export(module,args,names,out):
    module.eval()
    with torch.inference_mode(): expected=module(*args)
    if isinstance(expected,torch.Tensor): expected=(expected,)
    torch.onnx.export(module,args,str(out),opset_version=17,input_names=names,
                      output_names=[f'output_{i}' for i in range(len(expected))],dynamo=False)
    graph=onnx.load(str(out));onnx.checker.check_model(graph)
    session=ort.InferenceSession(str(out),providers=['CPUExecutionProvider'])
    feed={n:a.detach().numpy() for n,a in zip(names,args) if n in {i.name for i in session.get_inputs()}}
    got=session.run(None,feed)
    errors=[]
    for original,actual in zip(expected,got):
        ref=original.detach().numpy();np.testing.assert_allclose(actual,ref,rtol=2e-3,atol=2e-4)
        errors.append(float(np.max(np.abs(actual-ref))))
    return {'file':out.name,'sha256':hashlib.sha256(out.read_bytes()).hexdigest(),
            'operators':sorted({n.op_type for n in graph.graph.node}),
            'cpu_max_abs_error':errors,'desktop_cpu_parity':True,'android_parity':False}

class ASR(torch.nn.Module):
    def __init__(self,m): super().__init__();self.m=m
    def forward(self,fbank,offset,cache_size,att_cache,cnn_cache):
        return self.m(fbank,offset,cache_size,att_cache,cnn_cache)

def main():
    p=argparse.ArgumentParser();p.add_argument('--upstream',type=pathlib.Path,required=True)
    p.add_argument('--checkpoints',type=pathlib.Path,required=True)
    p.add_argument('--out',type=pathlib.Path,required=True)
    p.add_argument('--component',choices=['gtm','asr'],required=True);a=p.parse_args()
    a.out.mkdir(parents=True,exist_ok=True);torch.set_num_threads(1);torch.manual_seed(7)
    report={'android_ready':False,'missing':['streaming DiT cache/offset ONNX rewrite','WavLM + ECAPA reference encoder export','Vocos decode/ISTFT export','Kaldi fbank native parity','BN interpolation cadence correction','Android multi-chunk parity'], 'components':[]}
    try:
        if a.component=='gtm':
            sys.path.insert(0,str(a.upstream.resolve()/'runtime'))
            from src.dit import GlobalTimbreMemory
            model=GlobalTimbreMemory()
            weights=load_file(str(a.checkpoints/'meanvc2_40ms_40ms.safetensors'))
            gtm={k[4:]:v for k,v in weights.items() if k.startswith('gtm.')}
            model.load_state_dict(gtm,strict=True)
            # More than one reference must produce correct conditioning.
            record=check_export(model,(torch.randn(1,256),),['speaker'],a.out/'gtm.onnx')
            sess=ort.InferenceSession(str(a.out/'gtm.onnx'),providers=['CPUExecutionProvider'])
            for _ in range(3):
                spk=torch.randn(1,256)
                with torch.no_grad(): expected=model(spk)
                got=sess.run(None,{'speaker':spk.numpy()})
                for e,g in zip(expected,got): np.testing.assert_allclose(g,e.numpy(),rtol=2e-3,atol=2e-4)
            report['components'].append(record)
        else:
            model=ASR(torch.jit.load(str(a.checkpoints/'fastu2pp_80ms.pt'),map_location='cpu').eval())
            args=(torch.randn(1,11,80),torch.tensor(4),torch.tensor(4),torch.zeros(6,4,4,128),torch.zeros(6,1,256,8))
            record=check_export(model,args,['fbank','offset','cache_size','att_cache','cnn_cache'],a.out/'asr_80ms.onnx')
            # Chained cache parity checks, not only first-chunk tracing.
            session=ort.InferenceSession(str(a.out/'asr_80ms.onnx'),providers=['CPUExecutionProvider'])
            att=args[3];cnn=args[4];ort_att=att.numpy();ort_cnn=cnn.numpy()
            for chunk in range(20):
                fb=torch.randn(1,11,80);off=torch.tensor(4+2*chunk)
                with torch.no_grad(): expected=model(fb,off,args[2],att,cnn)
                att,cnn=expected[1:]
                values={'fbank':fb.numpy(),'offset':off.numpy(),'cache_size':args[2].numpy(),'att_cache':ort_att,'cnn_cache':ort_cnn}
                got=session.run(None,{i.name:values[i.name] for i in session.get_inputs()})
                for e,g in zip(expected,got): np.testing.assert_allclose(g,e.numpy(),rtol=2e-3,atol=2e-4)
                ort_att,ort_cnn=got[1:]
            record['chained_chunks']=20;report['components'].append(record)
    except Exception as e:
        report['error']=f'{type(e).__name__}: {e}'
        (a.out/f'{a.component}-report.json').write_text(json.dumps(report,indent=2))
        raise
    (a.out/f'{a.component}-report.json').write_text(json.dumps(report,indent=2))
    print(json.dumps(report,indent=2))
if __name__=='__main__':main()
