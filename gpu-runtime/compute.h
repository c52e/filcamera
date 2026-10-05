#pragma once
#include "runtime.h"
#include "tracking.h"
#include <vector>
#include <stdexcept>
#include <string>
#include <cstring>

namespace fc {
inline void check(VkResult r,const char* label) {if(r!=VK_SUCCESS) throw std::runtime_error(std::string(label)+": "+std::to_string(r));}
struct Buffer {
    FcGpuContext* g;
    VkBuffer buffer{};VkDeviceMemory memory{};void* mapped=nullptr;VkDeviceSize bytes;
    Buffer(FcGpuContext* context,VkDeviceSize size,bool host):g(context),bytes(size) {
        try {
            if(!size || size>g->properties.limits.maxStorageBufferRange) throw std::runtime_error("GPU buffer size unsupported");
            VkBufferCreateInfo ci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};ci.size=size;ci.usage=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_TRANSFER_SRC_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            check(vkCreateBuffer(g->device,&ci,nullptr,&buffer),"create GPU buffer");
            VkMemoryRequirements req;vkGetBufferMemoryRequirements(g->device,buffer,&req);
            uint32_t type=UINT32_MAX;const auto required=host?(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT):VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
            for(uint32_t i=0;i<g->memory.memoryTypeCount;++i) if((req.memoryTypeBits&(1u<<i)) && (g->memory.memoryTypes[i].propertyFlags&required)==required) {type=i;if(!host || (g->memory.memoryTypes[i].propertyFlags&VK_MEMORY_PROPERTY_HOST_CACHED_BIT)) break;}
            if(type==UINT32_MAX) throw std::runtime_error("GPU memory type unsupported");
            VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};ai.allocationSize=req.size;ai.memoryTypeIndex=type;
            check(vkAllocateMemory(g->device,&ai,nullptr,&memory),"allocate GPU memory");check(vkBindBufferMemory(g->device,buffer,memory,0),"bind GPU memory");
            if(host) check(vkMapMemory(g->device,memory,0,size,0,&mapped),"map staging buffer");
        } catch(...) {close();throw;}
    }
    Buffer(const Buffer&)=delete;Buffer& operator=(const Buffer&)=delete;
    void close() noexcept {if(mapped) vkUnmapMemory(g->device,memory);if(buffer) vkDestroyBuffer(g->device,buffer,nullptr);if(memory) vkFreeMemory(g->device,memory,nullptr);mapped=nullptr;buffer={};memory={};}
    ~Buffer(){close();}
    VkDescriptorBufferInfo descriptor() const {return {buffer,0,bytes};}
};
class Compute {
    FcGpuContext* g;
    VkShaderModule shader{};VkDescriptorSetLayout setLayout{};VkPipelineLayout layout{};VkPipeline pipeline{};
    VkDescriptorPool descriptors{};VkDescriptorSet set{};VkCommandPool pool{};VkCommandBuffer command{};VkFence fence{};
    bool pending=false;
    std::vector<VkDescriptorType> types;
    uint32_t groupSize=64;
public:
    Compute(FcGpuContext* context,const uint32_t* code,size_t bytes,uint32_t bindings,uint32_t pushBytes,const std::vector<VkDescriptorType>& bindingTypes={},uint32_t workgroup=64):g(context),types(bindingTypes),groupSize(workgroup) {
        try {
            if(types.empty()) types.assign(bindings,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
            if(types.size()!=bindings) throw std::runtime_error("Compute binding count mismatch");
            VkShaderModuleCreateInfo si{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};si.codeSize=bytes;si.pCode=code;check(vkCreateShaderModule(g->device,&si,nullptr,&shader),"create compute shader");
            std::vector<VkDescriptorSetLayoutBinding> b(bindings);for(uint32_t i=0;i<bindings;++i) b[i]={i,types[i],1,VK_SHADER_STAGE_COMPUTE_BIT,nullptr};
            VkDescriptorSetLayoutCreateInfo di{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};di.bindingCount=bindings;di.pBindings=b.data();check(vkCreateDescriptorSetLayout(g->device,&di,nullptr,&setLayout),"create compute layout");
            VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT,0,pushBytes};VkPipelineLayoutCreateInfo li{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};li.setLayoutCount=1;li.pSetLayouts=&setLayout;li.pushConstantRangeCount=1;li.pPushConstantRanges=&range;check(vkCreatePipelineLayout(g->device,&li,nullptr,&layout),"create pipeline layout");
            if(!groupSize || groupSize>g->properties.limits.maxComputeWorkGroupInvocations || groupSize>g->properties.limits.maxComputeWorkGroupSize[0]) throw std::runtime_error("Compute workgroup unsupported");
            VkSpecializationMapEntry entry{0,0,sizeof(groupSize)};VkSpecializationInfo spec{1,&entry,sizeof(groupSize),&groupSize};
            VkComputePipelineCreateInfo pi{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};pi.layout=layout;pi.stage={VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};pi.stage.stage=VK_SHADER_STAGE_COMPUTE_BIT;pi.stage.module=shader;pi.stage.pName="main";pi.stage.pSpecializationInfo=&spec;check(vkCreateComputePipelines(g->device,{},1,&pi,nullptr,&pipeline),"create compute pipeline");
            std::vector<VkDescriptorPoolSize> sizes;
            for(auto type:types) {bool found=false;for(auto& s:sizes) if(s.type==type) {++s.descriptorCount;found=true;break;}if(!found) sizes.push_back({type,1});}
            VkDescriptorPoolCreateInfo dp{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};dp.maxSets=1;dp.poolSizeCount=uint32_t(sizes.size());dp.pPoolSizes=sizes.data();check(vkCreateDescriptorPool(g->device,&dp,nullptr,&descriptors),"create descriptor pool");
            VkDescriptorSetAllocateInfo da{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};da.descriptorPool=descriptors;da.descriptorSetCount=1;da.pSetLayouts=&setLayout;check(vkAllocateDescriptorSets(g->device,&da,&set),"allocate descriptor set");
            VkCommandPoolCreateInfo cp{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};cp.queueFamilyIndex=g->family;cp.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;check(vkCreateCommandPool(g->device,&cp,nullptr,&pool),"create command pool");
            VkCommandBufferAllocateInfo ca{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};ca.commandPool=pool;ca.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;ca.commandBufferCount=1;check(vkAllocateCommandBuffers(g->device,&ca,&command),"allocate command");
            VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};check(vkCreateFence(g->device,&fi,nullptr,&fence),"create fence");
        } catch(...) {close();throw;}
    }
    Compute(const Compute&)=delete;Compute& operator=(const Compute&)=delete;
    ~Compute(){close();}
    void close() noexcept {
        if(pending || !(fc_gpu_optimizations()&FC_COMPLETED_RELEASE)) vkDeviceWaitIdle(g->device);
        if(fence) vkDestroyFence(g->device,fence,nullptr);if(pool) vkDestroyCommandPool(g->device,pool,nullptr);
        if(descriptors) vkDestroyDescriptorPool(g->device,descriptors,nullptr);if(pipeline) vkDestroyPipeline(g->device,pipeline,nullptr);
        if(layout) vkDestroyPipelineLayout(g->device,layout,nullptr);if(setLayout) vkDestroyDescriptorSetLayout(g->device,setLayout,nullptr);if(shader) vkDestroyShaderModule(g->device,shader,nullptr);
        fence={};pool={};descriptors={};pipeline={};layout={};setLayout={};shader={};
    }
    void run(const std::vector<VkDescriptorBufferInfo>& infos,const void* push,uint32_t pushBytes,uint32_t invocations,
             const std::vector<VkDescriptorImageInfo>& images={},const VkImageMemoryBarrier* imageBarrier=nullptr) {
        if(infos.size()!=types.size()) throw std::runtime_error("Compute descriptor count mismatch");
        const uint32_t groups=(invocations+groupSize-1)/groupSize;
        if(!groups) throw std::runtime_error("Empty compute dispatch");
        const uint32_t gx=std::min(groups,g->properties.limits.maxComputeWorkGroupCount[0]),gy=(groups+gx-1)/gx;
        if(gy>g->properties.limits.maxComputeWorkGroupCount[1]) throw std::runtime_error("Compute dispatch limit");
        std::vector<VkWriteDescriptorSet> writes(infos.size());
        for(size_t i=0;i<infos.size();++i) {auto& w=writes[i];w={VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};w.dstSet=set;w.dstBinding=i;w.descriptorCount=1;w.descriptorType=types[i];if(types[i]==VK_DESCRIPTOR_TYPE_STORAGE_BUFFER) w.pBufferInfo=&infos[i];else {if(images.size()!=types.size()) throw std::runtime_error("Compute image descriptor count mismatch");w.pImageInfo=&images[i];}}
        vkUpdateDescriptorSets(g->device,writes.size(),writes.data(),0,nullptr);
        check(vkResetCommandBuffer(command,0),"reset command");check(vkResetFences(g->device,1,&fence),"reset fence");
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};bi.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;check(vkBeginCommandBuffer(command,&bi),"begin command");
        if(imageBarrier) vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,0,nullptr,0,nullptr,1,imageBarrier);
        VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};barrier.srcAccessMask=VK_ACCESS_HOST_WRITE_BIT|VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_WRITE_BIT;barrier.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_HOST_BIT|VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,1,&barrier,0,nullptr,0,nullptr);
        vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_COMPUTE,layout,0,1,&set,0,nullptr);vkCmdPushConstants(command,layout,VK_SHADER_STAGE_COMPUTE_BIT,0,pushBytes,push);vkCmdDispatch(command,gx,gy,1);
        barrier.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT;barrier.dstAccessMask=VK_ACCESS_HOST_READ_BIT|VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_TRANSFER_READ_BIT;
        vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_HOST_BIT|VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,0,1,&barrier,0,nullptr,0,nullptr);
        check(vkEndCommandBuffer(command),"end command");VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};submit.commandBufferCount=1;submit.pCommandBuffers=&command;
        check(vkQueueSubmit(g->queue,1,&submit,fence),"submit command");pending=true;
        check(vkWaitForFences(g->device,1,&fence,VK_TRUE,UINT64_MAX),"wait command");pending=false;
    }
};
}
