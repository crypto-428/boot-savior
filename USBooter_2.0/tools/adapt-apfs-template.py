#!/usr/bin/env python3
"""Prototype of the APFS template adapter (mirrors ApfsTemplate.kt).
usage: adapt-apfs-template.py TEMPLATE_IMG BLOCKS LABEL OUT"""
import struct,sys,uuid
BS=4096;BPC=32768;CPC=126;M=0xFFFFFFFF
def fl(b):
    s1=s2=0
    for (w,) in struct.iter_unpack('<I',b[8:]):
        s1=(s1+w)%M; s2=(s2+s1)%M
    c1=M-((s1+s2)%M); c2=M-((s1+c1)%M)
    return struct.pack('<II',c1,c2)
def adapt(tpl,N,label,out):
    c=bytearray(tpl)
    used=max(i for i in range(len(c)//BS) if any(c[i*BS:(i+1)*BS]))+1
    assert N>=max(used+8,512)
    blk=lambda i:memoryview(c)[i*BS:(i+1)*BS]
    typ=lambda i:struct.unpack_from('<I',c,i*BS+24)[0]&0xFFFF
    chunks=(N+BPC-1)//BPC; cibs=(chunks+CPC-1)//CPC
    assert 2568+8*cibs+8<=4096
    sm=blk(23); cibaddr=struct.unpack_from('<Q',sm,2568)[0]
    cib=blk(cibaddr); xid=struct.unpack_from('<Q',cib,16)[0]
    bm=struct.unpack_from('<Q',cib,64)[0]
    old0=struct.unpack_from('<I',cib,56)[0]; free0=struct.unpack_from('<I',cib,60)[0]
    bitmap=blk(bm); extra=[]; b=used
    while len(extra)<cibs-1:
        if not (bitmap[b//8]>>(b%8))&1: extra.append(b); bitmap[b//8]|=1<<(b%8)
        b+=1
    c0=min(N,BPC); free0=free0+(c0-old0)-len(extra); totalfree=free0+(N-c0)
    cl=[cibaddr]+extra
    for k,a in enumerate(cl):
        x=blk(a)
        if k>0: x[:]=bytes(BS); x[8:40]=cib[8:40]; struct.pack_into('<Q',x,8,a)
        n=min(CPC,chunks-k*CPC); struct.pack_into('<II',x,32,k,n)
        for j in range(n):
            ch=k*CPC+j; o=40+j*32; cnt=min(BPC,N-ch*BPC)
            struct.pack_into('<QQIIQ',x,o,xid,ch*BPC,cnt,free0 if ch==0 else cnt,bm if ch==0 else 0)
        x[0:8]=fl(x)
    cu=uuid.uuid4().bytes; vu=uuid.uuid4().bytes
    maxfs=max(1,min(100,(N*BS+(512<<20)-1)//(512<<20)))
    for i in range(used):
        t=typ(i); x=blk(i)
        if t==1:
            struct.pack_into('<Q',x,40,N); x[72:88]=cu; struct.pack_into('<I',x,180,maxfs)
            e=struct.unpack_from('<Q',x,1312)[0]&0xFFFFFFFF
            struct.pack_into('<Q',x,1312,(((N+4095)//4096)<<32)|e)
        elif t==5:
            struct.pack_into('<QQIIQ',x,48,N,chunks,cibs,0,totalfree)
            struct.pack_into('<I',x,128,2568+8*cibs)
            for k,a in enumerate(cl): struct.pack_into('<Q',x,2568+8*k,a)
        elif t==0x0d:
            x[240:256]=vu; nm=label.encode()[:255]; x[704:960]=nm+bytes(256-len(nm))
        else: continue
        x[0:8]=fl(x)
    with open(out,'wb') as f: f.write(c[:used*BS]); f.truncate(N*BS)
if __name__=='__main__':
    adapt(open(sys.argv[1],'rb').read()[:300*BS],int(sys.argv[2]),sys.argv[3],sys.argv[4])
