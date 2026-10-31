// SPDX-License-Identifier: GPL-3.0-only
// FILCamera opposed-channel reconstruction, inspired by darktable's inpaint opposed.
// Algorithm reference: darktable src/iop/hlreconstruct/opposed.c (GPL-3.0-or-later),
// developed by garagecoder, Iain (G'MIC) and Jens Hannoschwalm.
// Independently expressed Vulkan passes: square mask dilation, deterministic GPU
// reduction and existing green-buffer scratch. No image/statistics CPU round trip.
// Work in white-balanced CFA, BEFORE lens shading. Thresholds therefore refer to
// actual sensor saturation, including the two independently calibrated green sites.
shared float highlightSums[768];
shared float highlightCounts[768];
uint color3(uint channel) { return channel==3 ? 2 : (channel==0 ? 0 : 1); }
uint maskWidth() { return (p[0]+2)/3; }
uint maskHeight() { return (p[1]+2)/3; }
float clipAt(int x,int y) {
    int xx=mirror(x,int(p[0])),yy=mirror(y,int(p[1]));
    return .987*f(25+p[16+site(xx,yy)]);
}
// A local RGB superpixel. Cube-root channel means make a robust opposing estimate.
float opposing(int x,int y,uint color) {
    vec3 sums=vec3(0),counts=vec3(0);
    for(int dy=-1;dy<=1;dy++) for(int dx=-1;dx<=1;dx++) {
        int xx=mirror(x+dx,int(p[0])),yy=mirror(y+dy,int(p[1]));
        uint cc=color3(p[16+site(xx,yy)]);
        sums[cc]+=c(xx,yy);counts[cc]+=1;
    }
    vec3 root=pow(max(sums/counts,vec3(0)),vec3(1.0/3.0));
    float v=color==0 ? .5*(root.y+root.z) : (color==1 ? .5*(root.x+root.z) : .5*(root.x+root.y));
    return v*v*v;
}
// Must be called by every invocation, including padded edge lanes, for reductions.
void highlights(uint pass,uint x,uint y) {
    uint lane=gl_LocalInvocationIndex;
    uint lanes=gl_WorkGroupSize.x*gl_WorkGroupSize.y;
    bool inside=x<p[0] && y<p[1];
    uint mw=maskWidth(),mh=maskHeight();
    if(pass==6) {
        if(x>=mw || y>=mh) return;
        uint mask=0;
        for(uint dy=0;dy<3;dy++) for(uint dx=0;dx<3;dx++) {
            uint xx=x*3+dx,yy=y*3+dy;
            if(xx<p[0] && yy<p[1] && c(int(xx),int(yy))>=clipAt(int(xx),int(yy)))
                mask|=1u<<color3(p[16+site(int(xx),int(yy))]);
        }
        p[p[46]+y*mw+x]=mask;
        if(mask!=0) atomicOr(p[52],1u);
    } else if(pass==7) {
        if(x>=mw || y>=mh || p[52]==0) return;
        uint mask=0;
        for(int dy=-3;dy<=3;dy++) for(int dx=-3;dx<=3;dx++) {
            int xx=int(x)+dx,yy=int(y)+dy;
            if(xx>=0 && yy>=0 && xx<int(mw) && yy<int(mh)) mask|=p[p[46]+uint(yy)*mw+uint(xx)];
        }
        p[p[47]+y*mw+x]=mask;
    } else if(pass==8 || pass==9) {
        if(p[52]==0) return; // uniform across every workgroup
        for(uint cc=0;cc<3;cc++) {highlightSums[cc*256+lane]=0;highlightCounts[cc*256+lane]=0;}
        if(pass==8 && inside) {
            uint channel=p[16+site(int(x),int(y))],cc=color3(channel);
            float v=c(int(x),int(y)),clip=clipAt(int(x),int(y));
            uint mask=p[p[47]+(y/3)*mw+x/3];
            if(v<clip && v>.2*clip && (mask&(1u<<cc))!=0) {
                highlightSums[cc*256+lane]=v-opposing(int(x),int(y),cc);
                highlightCounts[cc*256+lane]=1;
            }
        } else if(pass==9) {
            uint groups=p[50]*p[51];
            for(uint i=lane;i<groups;i+=lanes) for(uint cc=0;cc<3;cc++) {
                highlightSums[cc*256+lane]+=f(p[48]+i*6+cc);
                highlightCounts[cc*256+lane]+=f(p[48]+i*6+3+cc);
            }
        }
        barrier();
        for(uint stride=lanes/2;stride>0;stride/=2) {
            if(lane<stride) for(uint cc=0;cc<3;cc++) {
                highlightSums[cc*256+lane]+=highlightSums[cc*256+lane+stride];
                highlightCounts[cc*256+lane]+=highlightCounts[cc*256+lane+stride];
            }
            barrier();
        }
        if(lane==0) for(uint cc=0;cc<3;cc++) {
            float sum=highlightSums[cc*256],count=highlightCounts[cc*256];
            if(pass==8) {
                uint base=p[48]+(gl_WorkGroupID.y*p[50]+gl_WorkGroupID.x)*6;
                p[base+cc]=floatBitsToUint(sum);p[base+3+cc]=floatBitsToUint(count);
            } else p[p[49]+cc]=floatBitsToUint(count>100 ? sum/count : 0);
        }
    } else if(pass==10) {
        if(!inside || p[52]==0) return;
        uint channel=p[16+site(int(x),int(y))];float v=c(int(x),int(y));
        if(v>=clipAt(int(x),int(y))) v=max(v,opposing(int(x),int(y),color3(channel))+f(p[49]+color3(channel)));
        green[y*p[0]+x]=v; // separate destination: no read/write neighborhood races
    } else if(pass==11) {
        if(!inside) return;
        uint index=y*p[0]+x,channel=p[16+site(int(x),int(y))];
        float v=p[52]==0 ? cfa[index] : green[index];
        cfa[index]=v*shade(channel,x,y);
    }
}
