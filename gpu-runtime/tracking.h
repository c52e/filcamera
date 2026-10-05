#pragma once
#include "runtime.h"
// Apply only inside our compute implementations, not the Vulkan loader wrapper.
#define vkAllocateMemory fc_vkAllocateMemory
#define vkFreeMemory fc_vkFreeMemory
#define vkWaitForFences fc_vkWaitForFences
#define vkDeviceWaitIdle fc_vkDeviceWaitIdle
#define vkCmdDispatch fc_vkCmdDispatch
#define vkQueueSubmit fc_vkQueueSubmit
#define vkAllocateCommandBuffers fc_vkAllocateCommandBuffers
#define vkFreeCommandBuffers fc_vkFreeCommandBuffers
#define vkDestroyCommandPool fc_vkDestroyCommandPool
#define vkBeginCommandBuffer fc_vkBeginCommandBuffer
#define vkEndCommandBuffer fc_vkEndCommandBuffer
