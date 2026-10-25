import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { FulfillmentApp as App } from './FulfillmentApp'

function renderApp() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={client}>
      <App />
    </QueryClientProvider>,
  )
}

describe('SmartData operations console', () => {
  it('renders the selected case with bitemporal evidence and provenance', async () => {
    renderApp()

    expect(await screen.findByRole('heading', { name: '库存承诺冲突', level: 1 }, { timeout: 5_000 })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '证据时间线' })).toBeInTheDocument()
    expect(screen.getByText('业务有效时间')).toBeInTheDocument()
    expect(screen.getByText('系统记录时间')).toBeInTheDocument()
    expect(screen.getAllByText('COUNT-20260907-044').length).toBeGreaterThan(0)
    expect(screen.getByText('执行器回执与验证结论分离')).toBeInTheDocument()
    expect(screen.getAllByText('演示数据').length).toBeGreaterThan(0)
  })

  it('keeps approval, execution and independent verification as explicit steps', async () => {
    renderApp()
    const approvalButton = await screen.findByRole('button', { name: /进入策略校验与审批/ }, { timeout: 5_000 })
    fireEvent.click(approvalButton)

    const dialog = await screen.findByRole('dialog', { name: '动作审批' })
    expect(dialog).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '确认批准' }))
    expect(screen.getByRole('alert')).toHaveTextContent('至少 4 个字')

    fireEvent.change(screen.getByPlaceholderText('说明批准或驳回的业务判断…'), {
      target: { value: '同意预留替代库存，后续继续刷新质检记录。' },
    })
    fireEvent.click(screen.getByRole('button', { name: '确认批准' }))

    expect(await screen.findByRole('heading', { name: '审批已通过' })).toBeInTheDocument()
    expect(screen.getByText(/通过不等于执行成功/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '返回案件' }))
    const executeButton = await screen.findByRole('button', { name: '显式触发执行' })
    expect(screen.queryByText('执行器返回成功')).not.toBeInTheDocument()
    fireEvent.click(executeButton)

    expect(await screen.findByText('执行器返回成功')).toBeInTheDocument()
    expect(screen.getAllByText('WMS 已接收并返回库存预留回执。').length).toBeGreaterThan(0)
    const verifyButton = screen.getByRole('button', { name: '启动独立验证' })
    fireEvent.click(verifyButton)

    expect((await screen.findAllByText('独立验证通过')).length).toBeGreaterThan(0)
    expect(screen.getByText(/已生成 L1 经验候选/)).toBeInTheDocument()
  }, 15_000)

  it('supports memory review and evaluation health views', async () => {
    renderApp()
    await screen.findByRole('heading', { name: '库存承诺冲突', level: 1 }, { timeout: 5_000 })

    fireEvent.click(screen.getByRole('button', { name: '经验记忆' }))
    expect(await screen.findByRole('heading', { name: '经验记忆审核' })).toBeInTheDocument()
    expect(screen.getByText('候选经验必须保留来源，并由业务责任人批准后才能影响共享检索与动作建议。')).toBeInTheDocument()

    fireEvent.change(screen.getByPlaceholderText('记录适用边界或驳回原因…'), {
      target: { value: '适用范围清晰，同意进入共享检索。' },
    })
    fireEvent.click(screen.getByRole('button', { name: '批准发布' }))
    await waitFor(() => expect(screen.getAllByText('已发布').length).toBeGreaterThan(0))

    fireEvent.click(screen.getByRole('button', { name: '评测健康' }))
    expect(await screen.findByRole('heading', { name: '评测健康' })).toBeInTheDocument()
    expect(screen.getByText('关键链路可追溯')).toBeInTheDocument()
    expect(screen.getAllByText('fulfillment-holdout-cn@2026.09.07').length).toBeGreaterThan(0)
  }, 15_000)
})
